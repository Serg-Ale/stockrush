import { inject, Injectable, InjectionToken, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, retry, Subscription, tap, throwError, timer } from 'rxjs';
import { listOrders, streamOrders } from '../api';
import { AuthService } from '../auth/auth.service';
import { applyEvent, mergeSnapshot, Order, OrderStatusEvent, toOrder } from './order';

export type Connection = 'connecting' | 'live' | 'reconnecting';

type Message = { type: 'open' } | { type: 'status'; event: OrderStatusEvent };

/**
 * How long the stream may stay silent before it is treated as dead and reopened. The api sends a keep-alive comment
 * every 15 s, so three missed beats mean the connection is gone even though the browser was never told.
 */
export const STREAM_STALL_MS = new InjectionToken<number>('STREAM_STALL_MS', { factory: () => 45_000 });

const BASE_DELAY_MS = 1000;
const MAX_DELAY_MS = 15000;

/** 1 s, 2 s, 4 s ... up to 15 s. */
export function reconnectDelay(attempt: number): number {
  return Math.min(BASE_DELAY_MS * 2 ** (attempt - 1), MAX_DELAY_MS);
}

const isUnauthorized = (error: unknown) => error instanceof Error && /SSE failed: 401/.test(error.message);

/**
 * The signed-in user's orders, kept live. The stream is read with fetch because EventSource cannot send the bearer
 * token (ADR 0006). Whenever a connection opens the list is read again from `GET /orders`, so events missed while
 * disconnected are recovered; the api keeps no replay.
 */
@Injectable({ providedIn: 'root' })
export class OrderStreamService {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly stallMs = inject(STREAM_STALL_MS);

  readonly orders = signal<Order[]>([]);
  readonly connection = signal<Connection>('connecting');

  private subscription: Subscription | null = null;

  start(): void {
    if (this.subscription) return;
    this.connection.set('connecting');
    this.subscription = this.messages()
      .pipe(
        tap({ error: () => this.connection.set('reconnecting') }),
        retry({
          delay: (error, attempt) => (isUnauthorized(error) ? throwError(() => error) : timer(reconnectDelay(attempt))),
          resetOnSuccess: true,
        }),
      )
      .subscribe({
        next: (message) => this.handle(message),
        error: () => {
          // Only an unauthorized stream gets here: the session is over.
          this.subscription = null;
          this.auth.logout();
          void this.router.navigate(['/entrar']);
        },
      });
  }

  stop(): void {
    this.subscription?.unsubscribe();
    this.subscription = null;
    this.orders.set([]);
  }

  private handle(message: Message): void {
    if (message.type === 'open') {
      this.connection.set('live');
      void this.resync();
      return;
    }
    const next = applyEvent(this.orders(), message.event);
    if (next) {
      this.orders.set(next);
    } else {
      void this.resync();
    }
  }

  private async resync(): Promise<void> {
    try {
      const { data } = await listOrders({ throwOnError: true });
      this.orders.update((current) => mergeSnapshot(current, data.map(toOrder)));
    } catch {
      // A failed read is fixed by the next event or reconnection.
    }
  }

  /** One connection. Any end, clean or not, is an error to the caller, which reconnects. */
  private messages(): Observable<Message> {
    return new Observable<Message>((subscriber) => {
      const abort = new AbortController();
      let failure: unknown = null;
      let watchdog: ReturnType<typeof setTimeout> | undefined;
      const watch = () => {
        clearTimeout(watchdog);
        watchdog = setTimeout(() => {
          failure = new Error('The order stream went silent');
          abort.abort();
        }, this.stallMs);
      };
      watch();
      void (async () => {
        try {
          const { stream } = await streamOrders({
            signal: abort.signal,
            // The generated Angular client builds the SSE request headers from an HttpHeaders object, which loses the
            // bearer token, so the request is rebuilt here with the header set explicitly.
            onRequest: async (url, init) =>
              new Request(url, { ...init, headers: { Authorization: `Bearer ${this.auth.token() ?? ''}` } }),
            sseMaxRetryAttempts: 1,
            onSseError: (error) => (failure = error),
            onSseEvent: (e) => {
              watch();
              if (e.event === undefined && e.data === undefined) subscriber.next({ type: 'open' });
            },
          });
          for await (const data of stream) {
            subscriber.next({ type: 'status', event: data as unknown as OrderStatusEvent });
          }
          subscriber.error(failure ?? new Error('The order stream ended'));
        } catch (error) {
          subscriber.error(error);
        }
      })();
      return () => {
        clearTimeout(watchdog);
        abort.abort();
      };
    });
  }
}
