import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { client } from '../../api/client.gen';
import { provideHeyApiClient } from '../../api/client/client.gen';
import { Catalog } from './catalog';

const products = [
  { id: 1, name: 'Tênis Edição Limitada', availableQuantity: 5 },
  { id: 2, name: 'Boné Esgotado', availableQuantity: 0 },
];

async function render() {
  TestBed.configureTestingModule({
    providers: [provideHttpClient(), provideHttpClientTesting(), provideHeyApiClient(client)],
  });
  const http = TestBed.inject(HttpTestingController);
  const fixture = TestBed.createComponent(Catalog);
  fixture.detectChanges();
  (await vi.waitFor(() => http.expectOne('/products'))).flush(products);
  const page = fixture.nativeElement as HTMLElement;
  await vi.waitFor(() => {
    fixture.detectChanges();
    expect(page.querySelector('form')).not.toBeNull();
  });
  return { fixture, http, page };
}

describe('Catalog', () => {
  it('shows each product with its available quantity, or that it sold out', async () => {
    const { page } = await render();

    expect(page.textContent).toContain('Tênis Edição Limitada');
    expect(page.textContent).toContain('Disponível: 5');
    expect(page.textContent).toContain('Esgotado');
  });

  it('creates the order with an Idempotency-Key and confirms it', async () => {
    const { fixture, http, page } = await render();

    page.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    const request = await vi.waitFor(() => http.expectOne('/orders'));
    expect(request.request.headers.get('Idempotency-Key')).toBeTruthy();
    request.flush({ id: 9 }, { status: 201, statusText: 'Created' });
    (await vi.waitFor(() => http.expectOne('/products'))).flush(products);

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(document.body.textContent).toContain('Pedido #9 criado.');
    });
  });

  it('shows the insufficient-stock message and reloads the catalog', async () => {
    const { fixture, http, page } = await render();

    page.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    const request = await vi.waitFor(() => http.expectOne('/orders'));
    request.flush({ status: 409, detail: 'Insufficient stock' }, { status: 409, statusText: 'Conflict' });
    (await vi.waitFor(() => http.expectOne('/products'))).flush([{ id: 1, name: 'Tênis Edição Limitada', availableQuantity: 1 }]);

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(page.textContent).toContain('Disponível: 1');
    });
    expect(page.querySelector('[role="alert"]')?.textContent).toContain('Estoque insuficiente');
  });
});
