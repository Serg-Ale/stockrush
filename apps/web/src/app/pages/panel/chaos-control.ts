import { Component, inject, OnInit, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSliderModule } from '@angular/material/slider';
import { chaosErrorMessage, ChaosService, MAX_LATENCY_MS } from '../../demo/chaos.service';

/** Tunes, at runtime, how the payment service misbehaves: share of temporary failures and tok_slow latency. */
@Component({
  selector: 'app-chaos-control',
  imports: [ReactiveFormsModule, MatCardModule, MatSliderModule, MatFormFieldModule, MatInputModule, MatButtonModule],
  templateUrl: './chaos-control.html',
  styleUrl: './chaos-control.scss',
})
export class ChaosControl implements OnInit {
  private readonly chaos = inject(ChaosService);

  protected readonly maxLatency = MAX_LATENCY_MS;
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly applied = signal<string | null>(null);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    failurePercent: [0, [Validators.min(0), Validators.max(100)]],
    latencyMs: [2000, [Validators.required, Validators.min(0), Validators.max(MAX_LATENCY_MS), Validators.pattern(/^\d+$/)]],
  });

  ngOnInit(): void {
    void this.run(() => this.chaos.get());
  }

  protected apply(): void {
    if (this.form.invalid || this.busy()) return;
    const { failurePercent, latencyMs } = this.form.getRawValue();
    void this.run(() => this.chaos.set({ failureRate: failurePercent / 100, latencyMs }), true);
  }

  private async run(call: () => Promise<{ failureRate: number; latencyMs: number }>, announce = false): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      const values = await call();
      this.form.setValue({ failurePercent: Math.round(values.failureRate * 100), latencyMs: values.latencyMs });
      this.applied.set(announce ? `Aplicado: ${Math.round(values.failureRate * 100)}% de falhas, tok_slow em ${values.latencyMs} ms.` : null);
    } catch (error) {
      this.error.set(chaosErrorMessage(error));
    } finally {
      this.busy.set(false);
    }
  }
}
