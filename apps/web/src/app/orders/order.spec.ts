import { applyEvent, mergeSnapshot, Order } from './order';

const order = (id: number, status: Order['status'] = 'PENDING', reason: string | null = null): Order => ({
  id,
  productId: 1,
  quantity: 1,
  status,
  cancellationReason: reason,
});

describe('applyEvent', () => {
  it('changes only the order named by the event', () => {
    const result = applyEvent([order(2), order(1)], { orderId: 1, status: 'CANCELLED', cancellationReason: 'PAYMENT_DECLINED' });

    expect(result).toEqual([order(2), order(1, 'CANCELLED', 'PAYMENT_DECLINED')]);
  });

  it('says so when the order is not in the list', () => {
    expect(applyEvent([order(1)], { orderId: 9, status: 'CONFIRMED' })).toBeNull();
  });
});

describe('mergeSnapshot', () => {
  it('lists the newest order first', () => {
    expect(mergeSnapshot([], [order(1), order(3), order(2)]).map((o) => o.id)).toEqual([3, 2, 1]);
  });

  it('never takes a final order back to pending when the snapshot is older than an event', () => {
    const merged = mergeSnapshot([order(1, 'CONFIRMED')], [order(1, 'PENDING')]);

    expect(merged[0].status).toBe('CONFIRMED');
  });

  it('takes the snapshot when it moved on', () => {
    expect(mergeSnapshot([order(1)], [order(1, 'CANCELLED', 'EXPIRED')])[0].status).toBe('CANCELLED');
  });
});
