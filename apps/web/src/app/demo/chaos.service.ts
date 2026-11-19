import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** The chaos control of the payment service: share of attempts that fail, and the latency of `tok_slow`. */
export interface ChaosSettings {
  /** 0 to 1. */
  failureRate: number;
  latencyMs: number;
}

export const MAX_LATENCY_MS = 30_000;

export function chaosErrorMessage(error: unknown): string {
  const status = (error as { status?: unknown } | null)?.status;
  switch (status) {
    case 400:
      return `Informe uma taxa de 0 a 100% e uma latência de 0 a ${MAX_LATENCY_MS} ms.`;
    case 403:
      return 'Apenas administradores podem alterar o caos.';
    default:
      return 'Não foi possível falar com o serviço de pagamento. Tente novamente.';
  }
}

/** The payment service is not part of the api contract, so this talks to its `/chaos` endpoint directly. */
@Injectable({ providedIn: 'root' })
export class ChaosService {
  private readonly http = inject(HttpClient);

  get(): Promise<ChaosSettings> {
    return firstValueFrom(this.http.get<ChaosSettings>('/chaos'));
  }

  update(settings: ChaosSettings): Promise<ChaosSettings> {
    return firstValueFrom(this.http.put<ChaosSettings>('/chaos', settings));
  }
}
