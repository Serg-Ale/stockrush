import { Component, effect, ElementRef, input, OnDestroy, viewChild } from '@angular/core';
import { BarController, BarElement, CategoryScale, Chart, LinearScale, Tooltip } from 'chart.js';
import { RaceResult } from '../../demo/demo-race.service';

Chart.register(BarController, BarElement, CategoryScale, LinearScale, Tooltip);
Chart.defaults.font.family = "Geist, Roboto, sans-serif";

/** The three numbers the panel is about, in the order they are read. */
export function chartData(result: RaceResult) {
  const values = [result.sold, result.rejected, result.stockAfter];
  // The value goes in the label too: a bar of height zero (an empty stock) would otherwise show nothing.
  return { labels: ['Vendidos', 'Rejeitados', 'Estoque final'].map((label, i) => `${label}: ${values[i]}`), values };
}

/**
 * The theme defines its colors as `light-dark(...)`, which a canvas cannot read. Painting a probe element with the
 * variable makes the browser resolve it to a plain rgb() value for the current color scheme.
 */
function cssColor(name: string): string {
  const probe = document.createElement('span');
  probe.style.color = `var(${name})`;
  document.body.appendChild(probe);
  const resolved = getComputedStyle(probe).color;
  probe.remove();
  return resolved;
}

@Component({
  selector: 'app-result-chart',
  template: `<canvas #canvas role="img" [attr.aria-label]="label()"></canvas>`,
  styles: `:host { display: block; position: relative; height: 280px; }`,
})
export class ResultChart implements OnDestroy {
  readonly result = input.required<RaceResult>();

  private readonly canvas = viewChild.required<ElementRef<HTMLCanvasElement>>('canvas');
  private chart: Chart | null = null;

  protected label(): string {
    const r = this.result();
    return `Vendidos: ${r.sold}. Rejeitados: ${r.rejected}. Estoque final: ${r.stockAfter}.`;
  }

  constructor() {
    effect(() => {
      const { labels, values } = chartData(this.result());
      const colors = [cssColor('--mat-sys-primary'), cssColor('--mat-sys-error'), cssColor('--mat-sys-tertiary')];
      this.chart?.destroy();
      this.chart = new Chart(this.canvas().nativeElement, {
        type: 'bar',
        data: { labels, datasets: [{ data: values, backgroundColor: colors, borderRadius: 10, borderSkipped: false, maxBarThickness: 96 }] },
        options: {
          maintainAspectRatio: false,
          animation: matchMedia('(prefers-reduced-motion: reduce)').matches ? false : undefined,
          plugins: { legend: { display: false } },
          scales: {
            y: {
              beginAtZero: true,
              ticks: { precision: 0, color: cssColor('--mat-sys-on-surface-variant') },
              grid: { color: cssColor('--mat-sys-outline-variant') },
            },
            x: { ticks: { color: cssColor('--mat-sys-on-surface') }, grid: { display: false } },
          },
        },
      });
    });
  }

  ngOnDestroy(): void {
    this.chart?.destroy();
  }
}
