import { chartData } from './result-chart';

describe('chartData', () => {
  it('lays out sold, rejected and the final stock, in that order, with the value in each label', () => {
    const data = chartData({ productId: 1, buyers: 200, sold: 50, rejected: 150, failed: 0, stockBefore: 50, stockAfter: 0 });

    expect(data.labels).toEqual(['Vendidos: 50', 'Rejeitados: 150', 'Estoque final: 0']);
    expect(data.values).toEqual([50, 150, 0]);
  });
});
