import { Component, ElementRef, inject, OnInit, signal, viewChild } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatSliderModule } from '@angular/material/slider';
import { ChaosService, chaosErrorMessage, MAX_LATENCY_MS } from '../../demo/chaos.service';
import { animateTo } from '../../layout/motion';

/** The chaos control: how often the simulated payment fails and how slow `tok_slow` is, changed while the system runs. */
@Component({
  selector: 'app-chaos-control',
  imports: [ReactiveFormsModule, MatCardModule, MatSliderModule, MatButtonModule],
  templateUrl: './chaos-control.html',
  styleUrl: './chaos-control.scss',
})
export class ChaosControl implements OnInit {
  private readonly chaos = inject(ChaosService);

  private readonly failureFill = viewChild<ElementRef<HTMLElement>>('failureFill');
  private readonly latencyFill = viewChild<ElementRef<HTMLElement>>('latencyFill');

  protected readonly maxLatency = MAX_LATENCY_MS;
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  /** What the payment service is running with now: the form can hold values that were not applied yet. */
  protected readonly applied = signal<{ failurePercent: number; latencyMs: number } | null>(null);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    failurePercent: [0, [Validators.min(0), Validators.max(100)]],
    latencyMs: [0, [Validators.min(0), Validators.max(MAX_LATENCY_MS)]],
  });

  ngOnInit(): void {
    void this.load();
  }

  private async load(): Promise<void> {
    try {
      this.show(await this.chaos.get(), true);
    } catch (error) {
      this.error.set(chaosErrorMessage(error));
    }
  }

  protected async apply(): Promise<void> {
    if (this.form.invalid || this.busy()) return;
    const { failurePercent, latencyMs } = this.form.getRawValue();
    this.busy.set(true);
    this.error.set(null);
    try {
      this.show(await this.chaos.update({ failureRate: failurePercent / 100, latencyMs }), false);
    } catch (error) {
      this.error.set(chaosErrorMessage(error));
    } finally {
      this.busy.set(false);
    }
  }

  private show(settings: { failureRate: number; latencyMs: number }, syncForm: boolean): void {
    const failurePercent = Math.round(settings.failureRate * 100);
    this.applied.set({ failurePercent, latencyMs: settings.latencyMs });
    if (syncForm) this.form.setValue({ failurePercent, latencyMs: settings.latencyMs });
    const failure = this.failureFill();
    const latency = this.latencyFill();
    if (failure) animateTo(failure.nativeElement, { width: `${failurePercent}%` });
    if (latency) animateTo(latency.nativeElement, { width: `${(settings.latencyMs / MAX_LATENCY_MS) * 100}%` });
  }
}
