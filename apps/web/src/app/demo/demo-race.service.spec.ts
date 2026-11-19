import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { client } from '../api/client.gen';
import { provideHeyApiClient } from '../api/client/client.gen';
import { DemoRaceService, raceErrorMessage } from './demo-race.service';

describe('DemoRaceService', () => {
  it('posts the race and returns the summary', async () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideHeyApiClient(client)],
    });
    const http = TestBed.inject(HttpTestingController);

    const run = TestBed.inject(DemoRaceService).run({ productId: 3, buyers: 200, stock: 50, paymentToken: 'tok_approve' });
    const request = await vi.waitFor(() => http.expectOne('/admin/race'));
    expect(request.request.body).toEqual({ productId: 3, buyers: 200, stock: 50, paymentToken: 'tok_approve' });
    request.flush({ productId: 3, buyers: 200, sold: 50, rejected: 150, failed: 0, stockBefore: 50, stockAfter: 0 });

    expect(await run).toEqual({ productId: 3, buyers: 200, sold: 50, rejected: 150, failed: 0, stockBefore: 50, stockAfter: 0 });
  });
});

describe('raceErrorMessage', () => {
  it('explains the refusals', () => {
    expect(raceErrorMessage({ status: 403 })).toContain('administradores');
    expect(raceErrorMessage({ status: 400 })).toContain('500');
    expect(raceErrorMessage({ status: 404 })).toBe('Produto não encontrado.');
    expect(raceErrorMessage(new Error('offline'))).toContain('Não foi possível');
  });
});
