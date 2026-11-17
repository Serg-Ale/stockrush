import { Injectable } from '@angular/core';
import { listProducts } from '../api';

export interface Product {
  id: number;
  name: string;
  availableQuantity: number;
}

@Injectable({ providedIn: 'root' })
export class CatalogService {
  /** The generated types mark every field optional; the api always sends all three. */
  async list(): Promise<Product[]> {
    const { data } = await listProducts({ throwOnError: true });
    return data.map((p) => ({ id: p.id ?? 0, name: p.name ?? '', availableQuantity: p.availableQuantity ?? 0 }));
  }
}
