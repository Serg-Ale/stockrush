import { Component, inject, OnInit, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { CatalogService, Product } from '../../catalog/catalog.service';
import { DemoRaceService, MAX_BUYERS, raceErrorMessage, RaceResult } from '../../demo/demo-race.service';
import { PAYMENT_TOKENS } from '../../orders/orders.service';
import { ResultChart } from './result-chart';

/** The demo panel: fire many simultaneous orders at one product and see how many sold, how many were refused. */
@Component({
  selector: 'app-panel',
  imports: [ReactiveFormsModule, MatCardModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, ResultChart],
  templateUrl: './panel.html',
  styleUrl: './panel.scss',
})
export class Panel implements OnInit {
  private readonly catalog = inject(CatalogService);
  private readonly race = inject(DemoRaceService);

  protected readonly products = signal<Product[]>([]);
  protected readonly result = signal<RaceResult | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly tokens = PAYMENT_TOKENS;
  protected readonly maxBuyers = MAX_BUYERS;

  protected readonly form = inject(FormBuilder).group({
    productId: [null as number | null, Validators.required],
    buyers: [200, [Validators.required, Validators.min(1), Validators.max(MAX_BUYERS), Validators.pattern(/^\d+$/)]],
    stock: [50 as number | null, [Validators.min(0), Validators.max(10_000)]],
    paymentToken: ['tok_approve'],
  });

  ngOnInit(): void {
    void this.loadProducts();
  }

  private async loadProducts(): Promise<void> {
    try {
      const products = await this.catalog.list();
      this.products.set(products);
      this.form.patchValue({ productId: products[0]?.id ?? null });
    } catch {
      this.error.set('Não foi possível carregar os produtos.');
    }
  }

  protected async fire(): Promise<void> {
    if (this.form.invalid || this.busy()) return;
    const { productId, buyers, stock, paymentToken } = this.form.getRawValue();
    this.busy.set(true);
    this.error.set(null);
    try {
      this.result.set(
        await this.race.run({
          productId: productId!,
          buyers: buyers!,
          stock: stock ?? undefined,
          paymentToken: paymentToken ?? 'tok_approve',
        }),
      );
      await this.loadProducts();
    } catch (error) {
      this.error.set(raceErrorMessage(error));
    } finally {
      this.busy.set(false);
    }
  }
}
