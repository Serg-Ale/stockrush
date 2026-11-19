import { Component, computed, inject, input, output, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Product } from '../../catalog/catalog.service';
import { OrdersService, orderErrorMessage, PAYMENT_TOKENS, statusOf } from '../../orders/orders.service';

const PLACEHOLDER = '/img/placeholder-product.svg';

@Component({
  selector: 'app-product-card',
  imports: [ReactiveFormsModule, MatCardModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule],
  templateUrl: './product-card.html',
  styleUrl: './product-card.scss',
})
export class ProductCard {
  private readonly orders = inject(OrdersService);
  private readonly snackBar = inject(MatSnackBar);

  readonly product = input.required<Product>();
  /** Emitted after the stock may have changed, so the list reloads. */
  readonly stockChanged = output<void>();

  protected readonly broken = signal(false);
  /** The product's own picture, unless it has none or it failed to load: then the SVG placeholder. */
  protected readonly hasPhoto = computed(() => !!this.product().imageUrl && !this.broken());
  protected readonly photo = computed(() => (this.hasPhoto() ? this.product().imageUrl! : PLACEHOLDER));

  protected readonly tokens = PAYMENT_TOKENS;
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    quantity: [1, [Validators.required, Validators.min(1), Validators.pattern(/^\d+$/)]],
    paymentToken: ['tok_approve'],
  });

  protected async order(): Promise<void> {
    if (this.form.invalid || this.busy()) return;
    const { quantity, paymentToken } = this.form.getRawValue();
    this.busy.set(true);
    this.error.set(null);
    try {
      const id = await this.orders.place(this.product().id, quantity, paymentToken);
      this.snackBar.open(`Pedido #${id} criado.`, 'Ok', { duration: 4000 });
      this.stockChanged.emit();
    } catch (error) {
      this.error.set(orderErrorMessage(error));
      if (statusOf(error) === 409) {
        this.stockChanged.emit();
      }
    } finally {
      this.busy.set(false);
    }
  }
}
