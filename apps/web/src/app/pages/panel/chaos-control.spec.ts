import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ChaosControl } from './chaos-control';

async function render() {
  TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
  const http = TestBed.inject(HttpTestingController);
  const fixture = TestBed.createComponent(ChaosControl);
  fixture.detectChanges();
  (await vi.waitFor(() => http.expectOne('/chaos'))).flush({ failureRate: 0.25, latencyMs: 1500 });
  await vi.waitFor(() => expect(fixture.componentInstance['form'].getRawValue().failurePercent).toBe(25));
  return { fixture, http, page: fixture.nativeElement as HTMLElement };
}

describe('ChaosControl', () => {
  it('starts from what the payment service is using now', async () => {
    const { fixture } = await render();
    expect(fixture.componentInstance['form'].getRawValue()).toEqual({ failurePercent: 25, latencyMs: 1500 });
  });

  it('sends the new values as a rate between 0 and 1', async () => {
    const { fixture, http, page } = await render();
    fixture.componentInstance['form'].setValue({ failurePercent: 60, latencyMs: 4000 });

    page.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    const put = await vi.waitFor(() => http.expectOne({ method: 'PUT', url: '/chaos' }));
    expect(put.request.body).toEqual({ failureRate: 0.6, latencyMs: 4000 });
    put.flush({ failureRate: 0.6, latencyMs: 4000 });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(page.querySelector('[role="status"]')?.textContent).toContain('60% de falhas');
    });
  });

  it('says when the user is not an admin', async () => {
    const { fixture, http, page } = await render();

    page.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    (await vi.waitFor(() => http.expectOne({ method: 'PUT', url: '/chaos' }))).flush({}, { status: 403, statusText: 'Forbidden' });

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(page.querySelector('[role="alert"]')?.textContent).toContain('administradores');
    });
  });
});
