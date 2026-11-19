import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** What the payment service applies to every attempt: share of temporary failures, and how long tok_slow takes. */
export interface ChaosValues {
  /** 0 to 1. */
  failureRate: number;
  latencyMs: number;
}

export const MAX_LATENCY_MS = 30_000;

export function chaosErrorMessage(error: unknown): string {
  const status = (error as { status?: unknown } | null)?.status;
  switch (status) {
    case 400:
      return `Use uma taxa de 0 a 100% e uma latência de 0 a ${MAX_LATENCY_MS} ms.`;
    case 403:
      return 'Apenas administradores controlam o caos.';
    default:
      return 'Não foi possível falar com o serviço de pagamento.';
  }
}

/** The chaos control lives in the payment service, not the api; the dev proxy and the deployment route /chaos to it. */
@Injectable({ providedIn: 'root' })
export class ChaosService {
  private readonly http = inject(HttpClient);

  get(): Promise<ChaosValues> {
    return firstValueFrom(this.http.get<ChaosValues>('/chaos'));
  }

  set(values: ChaosValues): Promise<ChaosValues> {
    return firstValueFrom(this.http.put<ChaosValues>('/chaos', values));
  }
}
