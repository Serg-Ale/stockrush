import { Injectable } from '@angular/core';
import { listProducts } from '../api';

export interface Product {
  id: number;
  name: string;
  availableQuantity: number;
  /** A path inside the web app (never a URL), or null when the product has no picture. */
  imageUrl: string | null;
}

@Injectable({ providedIn: 'root' })
export class CatalogService {
  /** The generated types mark every field optional; the api always sends all three. */
  async list(): Promise<Product[]> {
    const { data } = await listProducts({ throwOnError: true });
    return data.map((p) => ({ id: p.id ?? 0, name: p.name ?? '', availableQuantity: p.availableQuantity ?? 0,
      imageUrl: p.imageUrl ?? null,
    }));
  }
}
