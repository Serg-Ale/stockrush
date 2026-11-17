# 0006. Server-sent events for order status

- Status: accepted
- Date: 2026-11-17

## Context

Payment is asynchronous, so an order moves from `PENDING` to `CONFIRMED` or
`CANCELLED` seconds after the customer clicked. The customer should see that
without reloading.

## Decision

`GET /orders/stream` is a server-sent events stream. Each time an order
changes state, its owner's open streams receive an `order-status` event with
`orderId`, `status` and, for a cancelled order, the customer-facing
`cancellationReason`. The internal reason is never selected for this event.

- **After commit only.** The listener's transitions are single autocommit
  statements; the event is sent after the statement returns, so a client never
  sees a state that was rolled back.
- **Owner only.** Streams are registered per authenticated user, and an event
  goes only to the streams of the order's owner.
- **In memory, one instance.** The registry of open streams is a map in the
  `api` process. That is enough for v1, which runs one `api` instance. With
  several instances an event could be consumed by an instance that does not
  hold the client's stream; the fix would be to fan the change out through the
  broker. It is out of scope.
- **Authentication.** The browser `EventSource` cannot send an `Authorization`
  header, and putting the JWT in the URL would leak it into logs. The Angular
  client reads the stream with `fetch` (through RxJS) and sends the header.
- **No replay.** A client that reconnects reads the current state with
  `GET /orders` and then listens again.
- **Keep-alive.** The server sends a comment line every 15 s. A connection
  that dies without a clean close (an api restart behind a proxy, for
  example) looks like silence to the browser, so the client reopens a stream
  that stays silent for 45 s, with backoff from 1 s to 15 s.

## Alternatives

- **WebSocket:** two-way, which this feature does not need, and it brings its
  own handshake and authentication story.
- **Polling:** simple, but adds delay and constant requests for a state that
  changes once per order.

## Consequences

- Nothing is stored for streams; a restart drops them and clients reconnect.
- A stream times out after 30 minutes and the client reconnects.
