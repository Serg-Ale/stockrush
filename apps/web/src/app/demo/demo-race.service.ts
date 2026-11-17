import { Injectable } from '@angular/core';
import { runDemoRace } from '../api';

export interface RaceRequest {
  productId: number;
  buyers: number;
  /** Replaces the product's available quantity before the race, so the demo can be repeated. */
  stock?: number;
  paymentToken: string;
}

export interface RaceResult {
  productId: number;
  buyers: number;
  sold: number;
  rejected: number;
  failed: number;
  stockBefore: number;
  stockAfter: number;
}

export const MAX_BUYERS = 500;

export function raceErrorMessage(error: unknown): string {
  const status = (error as { status?: unknown } | null)?.status;
  switch (status) {
    case 400:
      return `Informe de 1 a ${MAX_BUYERS} compradores e um estoque entre 0 e 10.000.`;
    case 403:
      return 'Apenas administradores podem disparar a disputa.';
    case 404:
      return 'Produto não encontrado.';
    case 429:
      return 'Muitas tentativas. Aguarde um instante.';
    default:
      return 'Não foi possível disparar a disputa. Tente novamente.';
  }
}

@Injectable({ providedIn: 'root' })
export class DemoRaceService {
  async run(request: RaceRequest): Promise<RaceResult> {
    const { data } = await runDemoRace({ body: request, throwOnError: true });
    return {
      productId: data.productId ?? request.productId,
      buyers: data.buyers ?? 0,
      sold: data.sold ?? 0,
      rejected: data.rejected ?? 0,
      failed: data.failed ?? 0,
      stockBefore: data.stockBefore ?? 0,
      stockAfter: data.stockAfter ?? 0,
    };
  }
}
