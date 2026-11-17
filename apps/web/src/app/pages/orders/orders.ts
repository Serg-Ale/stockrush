import { Component, computed, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { CatalogService } from '../../catalog/catalog.service';
import { REASON_LABEL, STATUS_LABEL } from '../../orders/order';
import { OrderStreamService } from '../../orders/order-stream.service';

@Component({
  selector: 'app-orders',
  imports: [MatProgressBarModule],
  templateUrl: './orders.html',
  styleUrl: './orders.scss',
})
export class Orders implements OnInit {
  private readonly stream = inject(OrderStreamService);
  private readonly catalog = inject(CatalogService);

  protected readonly orders = this.stream.orders;
  protected readonly connection = this.stream.connection;
  private readonly productNames = signal(new Map<number, string>());
  protected readonly rows = computed(() =>
    this.orders().map((order) => ({
      order,
      product: this.productNames().get(order.productId) ?? `Produto ${order.productId}`,
      status: STATUS_LABEL[order.status],
      reason: order.cancellationReason ? (REASON_LABEL[order.cancellationReason] ?? order.cancellationReason) : null,
    })),
  );

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stream.stop());
  }

  ngOnInit(): void {
    this.stream.start();
    void this.catalog
      .list()
      .then((products) => this.productNames.set(new Map(products.map((p) => [p.id, p.name]))))
      .catch(() => undefined);
  }
}
