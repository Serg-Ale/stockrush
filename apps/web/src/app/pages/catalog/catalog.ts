import { Component, inject, OnInit, signal } from '@angular/core';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { CatalogService, Product } from '../../catalog/catalog.service';
import { ProductCard } from './product-card';

@Component({
  selector: 'app-catalog',
  imports: [ProductCard, MatProgressBarModule],
  templateUrl: './catalog.html',
  styleUrl: './catalog.scss',
})
export class Catalog implements OnInit {
  private readonly catalog = inject(CatalogService);

  protected readonly products = signal<Product[]>([]);
  protected readonly loading = signal(true);
  protected readonly failed = signal(false);

  ngOnInit(): void {
    void this.load();
  }

  protected async load(): Promise<void> {
    try {
      this.products.set(await this.catalog.list());
      this.failed.set(false);
    } catch {
      this.failed.set(true);
    } finally {
      this.loading.set(false);
    }
  }
}
