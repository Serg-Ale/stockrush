import { Injectable } from '@angular/core';
import { createOrder } from '../api';

/** Demonstration payment tokens understood by the simulated payment service. */
export const PAYMENT_TOKENS = [
  { value: 'tok_approve', label: 'Aprovar pagamento' },
  { value: 'tok_decline', label: 'Recusar pagamento' },
  { value: 'tok_flaky', label: 'Falha temporária (aprova na 3ª tentativa)' },
  { value: 'tok_slow', label: 'Pagamento lento' },
  { value: 'tok_dead', label: 'Falha permanente' },
] as const;

interface Attempt {
  key: string;
  productId: number;
  quantity: number;
  paymentToken: string;
}

/** The HTTP status of a failure: the generated client rejects with the Problem Details body. */
export function statusOf(error: unknown): number | null {
  const status = (error as { status?: unknown } | null)?.status;
  return typeof status === 'number' ? status : null;
}

export function orderErrorMessage(error: unknown): string {
  switch (statusOf(error)) {
    case 409:
      return 'Estoque insuficiente para a quantidade pedida.';
    case 404:
      return 'Produto não encontrado.';
    case 400:
      return 'Confira a quantidade informada.';
    case 429:
      return 'Muitas tentativas. Aguarde um instante.';
    default:
      return 'Não foi possível criar o pedido. Tente novamente.';
  }
}

@Injectable({ providedIn: 'root' })
export class OrdersService {
  private attempt: Attempt | null = null;

  /**
   * Sends the order with an Idempotency-Key. The same key is reused while the same order is retried after a failure
   * that left the outcome unknown (network error, 5xx), so a retry can never create a second order. A definitive
   * answer (success or a 4xx) ends the attempt, and any change to the order starts a new one.
   */
  async place(productId: number, quantity: number, paymentToken: string): Promise<number> {
    const same =
      this.attempt?.productId === productId &&
      this.attempt.quantity === quantity &&
      this.attempt.paymentToken === paymentToken;
    const key = same ? this.attempt!.key : crypto.randomUUID();
    this.attempt = { key, productId, quantity, paymentToken };
    try {
      const { data } = await createOrder({
        body: { productId, quantity, paymentToken },
        headers: { 'Idempotency-Key': key },
        throwOnError: true,
      });
      this.attempt = null;
      return data.id ?? 0;
    } catch (error) {
      const status = statusOf(error);
      if (status !== null && status < 500) {
        this.attempt = null;
      }
      throw error;
    }
  }
}
