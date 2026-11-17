import { OrderResponse } from '../api';

export type OrderStatus = 'PENDING' | 'CONFIRMED' | 'CANCELLED';

export interface Order {
  id: number;
  productId: number;
  quantity: number;
  status: OrderStatus;
  cancellationReason: string | null;
}

/** The `order-status` event the api pushes when an order changes state (ADR 0006). */
export interface OrderStatusEvent {
  orderId: number;
  status: OrderStatus;
  cancellationReason?: string | null;
}

export const STATUS_LABEL: Record<OrderStatus, string> = {
  PENDING: 'Pendente',
  CONFIRMED: 'Confirmado',
  CANCELLED: 'Cancelado',
};

export const REASON_LABEL: Record<string, string> = {
  PAYMENT_DECLINED: 'Pagamento recusado',
  PAYMENT_UNAVAILABLE: 'Pagamento indisponível',
  EXPIRED: 'Pedido expirado',
};

/** The generated types mark every field optional; the api always sends them. */
export function toOrder(r: OrderResponse): Order {
  return {
    id: r.id ?? 0,
    productId: r.productId ?? 0,
    quantity: r.quantity ?? 0,
    status: (r.status as OrderStatus) ?? 'PENDING',
    cancellationReason: r.cancellationReason ?? null,
  };
}

/** The order with the event applied, or null when the event is about an order this list does not know yet. */
export function applyEvent(orders: Order[], event: OrderStatusEvent): Order[] | null {
  if (!orders.some((o) => o.id === event.orderId)) return null;
  return orders.map((o) =>
    o.id === event.orderId ? { ...o, status: event.status, cancellationReason: event.cancellationReason ?? null } : o,
  );
}

/**
 * Newest first. A snapshot read from the api can be older than an event that arrived while it was in flight; a final
 * state never goes back to PENDING (the api never reopens an order), so keep the final one.
 */
export function mergeSnapshot(current: Order[], snapshot: Order[]): Order[] {
  const known = new Map(current.map((o) => [o.id, o]));
  return snapshot
    .map((o) => {
      const existing = known.get(o.id);
      return existing && existing.status !== 'PENDING' && o.status === 'PENDING' ? existing : o;
    })
    .sort((a, b) => b.id - a.id);
}
