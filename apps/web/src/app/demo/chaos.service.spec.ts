import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { chaosErrorMessage, ChaosService } from './chaos.service';

describe('ChaosService', () => {
  it('reads and updates the payment service settings', async () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const http = TestBed.inject(HttpTestingController);
    const service = TestBed.inject(ChaosService);

    const read = service.get();
    http.expectOne('/chaos').flush({ failureRate: 0.25, latencyMs: 2000 });
    expect(await read).toEqual({ failureRate: 0.25, latencyMs: 2000 });

    const write = service.update({ failureRate: 0.5, latencyMs: 4000 });
    const request = http.expectOne('/chaos');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({ failureRate: 0.5, latencyMs: 4000 });
    request.flush({ failureRate: 0.5, latencyMs: 4000 });
    expect(await write).toEqual({ failureRate: 0.5, latencyMs: 4000 });
  });

  it('explains the refusals', () => {
    expect(chaosErrorMessage({ status: 403 })).toContain('administradores');
    expect(chaosErrorMessage({ status: 400 })).toContain('30000');
    expect(chaosErrorMessage(new Error('offline'))).toContain('Não foi possível');
  });
});
