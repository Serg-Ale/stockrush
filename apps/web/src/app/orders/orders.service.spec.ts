import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { client } from '../api/client.gen';
import { provideHeyApiClient } from '../api/client/client.gen';
import { OrdersService, orderErrorMessage } from './orders.service';

function setup() {
  TestBed.configureTestingModule({
    providers: [provideHttpClient(), provideHttpClientTesting(), provideHeyApiClient(client)],
  });
  return { orders: TestBed.inject(OrdersService), http: TestBed.inject(HttpTestingController) };
}

const created = { id: 7, productId: 1, quantity: 2, status: 'PENDING' };

describe('OrdersService', () => {
  it('sends the order with an Idempotency-Key and returns the new id', async () => {
    const { orders, http } = setup();

    const placed = orders.place(1, 2, 'tok_approve');
    const request = await vi.waitFor(() => http.expectOne('/orders'));
    expect(request.request.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/);
    expect(request.request.body).toEqual({ productId: 1, quantity: 2, paymentToken: 'tok_approve' });
    request.flush(created, { status: 201, statusText: 'Created' });

    expect(await placed).toBe(7);
  });

  it('reuses the key when the same order is retried after a server failure', async () => {
    const { orders, http } = setup();

    const first = orders.place(1, 2, 'tok_approve');
    const failed = await vi.waitFor(() => http.expectOne('/orders'));
    const key = failed.request.headers.get('Idempotency-Key');
    failed.flush({ status: 503 }, { status: 503, statusText: 'Service Unavailable' });
    await expect(first).rejects.toBeTruthy();

    const retry = orders.place(1, 2, 'tok_approve');
    const again = await vi.waitFor(() => http.expectOne('/orders'));
    expect(again.request.headers.get('Idempotency-Key')).toBe(key);
    again.flush(created, { status: 201, statusText: 'Created' });
    await retry;
  });

  it('starts a new key after a definitive answer or when the order changes', async () => {
    const { orders, http } = setup();

    const first = orders.place(1, 2, 'tok_approve');
    const rejected = await vi.waitFor(() => http.expectOne('/orders'));
    const key = rejected.request.headers.get('Idempotency-Key');
    rejected.flush({ status: 409 }, { status: 409, statusText: 'Conflict' });
    await expect(first).rejects.toMatchObject({ status: 409 });

    const second = orders.place(1, 2, 'tok_approve');
    const next = await vi.waitFor(() => http.expectOne('/orders'));
    expect(next.request.headers.get('Idempotency-Key')).not.toBe(key);
    next.flush({ status: 503 }, { status: 503, statusText: 'Service Unavailable' });
    await expect(second).rejects.toBeTruthy();

    const changed = orders.place(1, 3, 'tok_approve');
    const other = await vi.waitFor(() => http.expectOne('/orders'));
    expect(other.request.headers.get('Idempotency-Key')).not.toBe(next.request.headers.get('Idempotency-Key'));
    other.flush(created, { status: 201, statusText: 'Created' });
    await changed;
  });
});

describe('orderErrorMessage', () => {
  it('says clearly when the stock ran out', () => {
    expect(orderErrorMessage({ status: 409 })).toBe('Estoque insuficiente para a quantidade pedida.');
  });

  it('covers the other failures with a generic fallback', () => {
    expect(orderErrorMessage({ status: 404 })).toBe('Produto não encontrado.');
    expect(orderErrorMessage({ status: 429 })).toContain('Muitas tentativas');
    expect(orderErrorMessage(new Error('offline'))).toContain('Não foi possível');
  });
});
