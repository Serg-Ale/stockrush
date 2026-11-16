# stockrush

An order and payment platform with limited stock: customers compete for the last units of a product, and the system shows that it never sells more than it has, even when payments fail.

Terms are in English, as in the code and the diagrams. The Portuguese UI text and the issues use the pt-BR equivalent given for each term.

## Language

**Order** (pedido):
A customer's request to buy a quantity of a single product.
_Avoid_: Purchase, sale, transaction

**Order status** (status do pedido):
Where an order is in its life: `PENDING` (waiting for a payment result), `CONFIRMED` (paid) or `CANCELLED` (will not be fulfilled). `CONFIRMED` and `CANCELLED` are final.
_Avoid_: State, phase

**Stock reservation** (reserva de estoque):
The units set aside for an order at the moment it is created, so no one else can buy them while payment is processed. A reservation ends in a sale (`CONFIRMED`) or is returned to stock (`CANCELLED`).
_Avoid_: Hold, lock, allocation

**Payment result** (resultado de pagamento):
The outcome the payment service reports for an order: `APPROVED`, `DECLINED` or `EXHAUSTED`.
_Avoid_: Payment status, payment response

**Exhausted** (esgotado):
A payment result meaning every attempt to process the payment failed temporarily, or the request could never be processed, so no decision was reached. It is distinct from `DECLINED`, where the payment was refused.
_Avoid_: Failed, timed out, errored

**First result wins** (primeiro resultado vence):
Once an order is final, a later payment result that contradicts it is discarded and counted, never applied.
_Avoid_: Last write wins, reopen

**Cancellation reason** (motivo do cancelamento):
Why a cancelled order was cancelled, shown to the customer as one of three values: `PAYMENT_DECLINED`, `PAYMENT_UNAVAILABLE` or `EXPIRED`. A finer internal reason is kept for operators.
_Avoid_: Error code, failure cause

**Expired order** (pedido expirado):
An order that stayed `PENDING` longer than the allowed time without any payment result, and was cancelled for that reason.
_Avoid_: Stale order, abandoned order

**Magic token** (token mágico):
A fixed payment token that forces a known behavior from the simulated payment service: `tok_approve`, `tok_decline`, `tok_flaky`, `tok_slow` or `tok_dead`.
_Avoid_: Test card, fake token

**Chaos control** (controle de caos):
The admin setting that changes the simulated payment service's failure rate and latency while the system runs.
_Avoid_: Fault injection, failure toggle

**Demo panel** (painel de demonstração):
The admin-only screen that fires many simultaneous orders on one product and shows how many were sold, how many were rejected and the final stock.
_Avoid_: Dashboard, load test
