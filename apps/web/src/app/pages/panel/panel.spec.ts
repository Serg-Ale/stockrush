import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, input } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { client } from '../../api/client.gen';
import { provideHeyApiClient } from '../../api/client/client.gen';
import { RaceResult } from '../../demo/demo-race.service';
import { Panel } from './panel';
import { ResultChart } from './result-chart';

/** jsdom has no canvas, so the chart itself is replaced; its data is tested on its own. */
@Component({ selector: 'app-result-chart', template: '<div data-testid="chart">{{ result().sold }}</div>' })
class StubChart {
  readonly result = input.required<RaceResult>();
}

const products = [{ id: 3, name: 'USB-C Hub', availableQuantity: 20 }];

async function render() {
  TestBed.configureTestingModule({
    providers: [provideHttpClient(), provideHttpClientTesting(), provideHeyApiClient(client)],
  }).overrideComponent(Panel, { remove: { imports: [ResultChart] }, add: { imports: [StubChart] } });
  const http = TestBed.inject(HttpTestingController);
  const fixture = TestBed.createComponent(Panel);
  fixture.detectChanges();
  (await vi.waitFor(() => http.expectOne('/products'))).flush(products);
  const page = fixture.nativeElement as HTMLElement;
  await vi.waitFor(() => {
    fixture.detectChanges();
    expect(fixture.componentInstance['form'].controls.productId.value).toBe(3);
  });
  return { fixture, http, page };
}

describe('Panel', () => {
  it('fires the race with the form values and shows the result', async () => {
    const { fixture, http, page } = await render();

    page.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    const request = await vi.waitFor(() => http.expectOne('/admin/race'));
    expect(request.request.body).toEqual({ productId: 3, buyers: 200, stock: 50, paymentToken: 'tok_approve' });
    request.flush({ productId: 3, buyers: 200, sold: 50, rejected: 150, failed: 0, stockBefore: 50, stockAfter: 0 });
    (await vi.waitFor(() => http.expectOne('/products'))).flush(products);

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(page.querySelector('[role="status"]')?.textContent).toContain('50 vendidos, 150 rejeitados, estoque final 0');
    });
    expect(page.querySelector('[data-testid="chart"]')?.textContent).toBe('50');
  });

  it('shows why the race was refused', async () => {
    const { fixture, http, page } = await render();

    page.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    (await vi.waitFor(() => http.expectOne('/admin/race'))).flush({ status: 403 }, { status: 403, statusText: 'Forbidden' });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(page.querySelector('[role="alert"]')?.textContent).toContain('administradores');
    });
    expect(page.querySelector('[data-testid="chart"]')).toBeNull();
  });
});
