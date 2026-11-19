import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import gsap from 'gsap';
import { ChaosControl } from './chaos-control';

function setReducedMotion(reduce: boolean) {
  vi.stubGlobal('matchMedia', (query: string) => ({ matches: reduce && query.includes('reduce'), media: query }));
}

async function render() {
  TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
  const http = TestBed.inject(HttpTestingController);
  const fixture = TestBed.createComponent(ChaosControl);
  fixture.detectChanges();
  http.expectOne('/chaos').flush({ failureRate: 0.2, latencyMs: 2000 });
  await vi.waitFor(() => {
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="status"]')).not.toBeNull();
  });
  return { fixture, http, page: fixture.nativeElement as HTMLElement };
}

afterEach(() => vi.unstubAllGlobals());

describe('ChaosControl', () => {
  it('starts from what the payment service is running with', async () => {
    setReducedMotion(true);
    const { page } = await render();

    expect(page.querySelector('[role="status"]')?.textContent).toContain('20% de falha, 2000 ms de latência');
  });

  it('sends the new failure rate and latency', async () => {
    setReducedMotion(true);
    const { fixture, http, page } = await render();

    fixture.componentInstance['form'].setValue({ failurePercent: 60, latencyMs: 5000 });
    page.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    const request = await vi.waitFor(() => http.expectOne('/chaos'));
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({ failureRate: 0.6, latencyMs: 5000 });
    request.flush({ failureRate: 0.6, latencyMs: 5000 });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(page.querySelector('[role="status"]')?.textContent).toContain('60% de falha, 5000 ms de latência');
    });
  });

  it('shows why the change was refused', async () => {
    setReducedMotion(true);
    const { fixture, http, page } = await render();

    page.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    (await vi.waitFor(() => http.expectOne('/chaos'))).flush({}, { status: 403, statusText: 'Forbidden' });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(page.querySelector('[role="alert"]')?.textContent).toContain('administradores');
    });
  });

  it('jumps to the new meter value, without animating, when the user prefers reduced motion', async () => {
    setReducedMotion(true);
    const to = vi.spyOn(gsap, 'to');
    const { page } = await render();

    expect(to).not.toHaveBeenCalled();
    expect(page.querySelector<HTMLElement>('.fill')!.style.width).toBe('20%');
  });

  it('animates the meters otherwise', async () => {
    setReducedMotion(false);
    const to = vi.spyOn(gsap, 'to');
    await render();

    expect(to).toHaveBeenCalledWith(expect.any(HTMLElement), expect.objectContaining({ width: '20%' }));
  });
});
