import { DecimalPipe } from '@angular/common';
import { Component, DestroyRef, ElementRef, afterNextRender, computed, inject, input, signal } from '@angular/core';
import { compact, niceMax } from './time-series-chart.component';

export interface HistogramBin {
  from: number;
  to: number;
  count: number;
}

export interface ThresholdMarker {
  value: number;
  label: string;
}

const HEIGHT = 180;
const PAD = { top: 18, right: 12, bottom: 28, left: 48 };

/**
 * Single-series histogram (SVG, real pixel width) with labelled threshold markers. Hovering a bin shows its range
 * and count; a visually hidden table carries the same data for assistive technology.
 */
@Component({
  selector: 'app-histogram',
  imports: [DecimalPipe],
  template: `
    <div class="chart" (mouseleave)="hover.set(null)">
      @if (width() > 0 && bins().length > 0) {
        <svg [attr.width]="width()" [attr.height]="height" role="img" [attr.aria-label]="label()">
          @for (t of ticks(); track t.value) {
            <line class="grid" [attr.x1]="pad.left" [attr.x2]="width() - pad.right" [attr.y1]="t.y" [attr.y2]="t.y" />
            <text class="axis" [attr.x]="pad.left - 8" [attr.y]="t.y + 4" text-anchor="end">{{ t.text }}</text>
          }
          @for (b of bars(); track b.i) {
            <rect class="hit" [attr.x]="b.x" [attr.y]="pad.top" [attr.width]="b.w" [attr.height]="plotHeight()"
              (mouseenter)="hover.set(b.i)" />
            <path class="mark" [class.dim]="hover() !== null && hover() !== b.i" [attr.d]="b.d" />
          }
          @for (x of xLabels(); track x.text) {
            <text class="axis" [attr.x]="x.x" [attr.y]="height - 8" text-anchor="middle">{{ x.text }}</text>
          }
          @for (m of markers(); track m.label) {
            <line class="marker" [attr.x1]="m.x" [attr.x2]="m.x" [attr.y1]="pad.top - 6" [attr.y2]="height - pad.bottom" />
            <text class="marker-label" [attr.x]="m.x + 3" [attr.y]="pad.top - 8">{{ m.label }}</text>
          }
        </svg>
        @if (hoverBin(); as h) {
          <div class="tooltip" [style.left.px]="h.left" role="status">
            <div class="tt-key">Score {{ h.bin.from | number: '1.3-3' }}–{{ h.bin.to | number: '1.3-3' }}</div>
            <div class="tt-val">{{ h.bin.count | number }} {{ unit() }}</div>
          </div>
        }
      }
      <table class="visually-hidden">
        <caption>{{ label() }}</caption>
        <thead><tr><th scope="col">Score from</th><th scope="col">Score to</th><th scope="col">{{ unit() }}</th></tr></thead>
        <tbody>
          @for (b of bins(); track b.from) {
            <tr><td>{{ b.from }}</td><td>{{ b.to }}</td><td>{{ b.count }}</td></tr>
          }
        </tbody>
      </table>
    </div>
  `,
  styles: `
    :host { display: block; width: 100%; }
    .chart { position: relative; width: 100%; min-height: 180px; }
    svg { display: block; }
    .grid { stroke: var(--aiq-border); stroke-width: 1; }
    .axis { fill: var(--aiq-text-muted); font-size: 11px; font-variant-numeric: tabular-nums; }
    .mark { fill: var(--aiq-series-1); transition: opacity 120ms; }
    .mark.dim { opacity: 0.45; }
    .hit { fill: transparent; }
    .marker { stroke: var(--aiq-text); stroke-width: 1; stroke-dasharray: 3 3; }
    .marker-label { fill: var(--aiq-text); font-size: 10px; }
    .tooltip {
      position: absolute; top: 0; transform: translateX(-50%); pointer-events: none; white-space: nowrap;
      background: var(--aiq-card); border: 1px solid var(--aiq-border); border-radius: 8px;
      box-shadow: 0 4px 12px rgba(11, 31, 58, 0.12); padding: 6px 10px; font-size: 0.78rem;
    }
    .tt-key { color: var(--aiq-text-muted); }
    .tt-val { color: var(--aiq-text); font-weight: 600; font-variant-numeric: tabular-nums; }
    .visually-hidden {
      position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap;
    }
  `,
})
export class HistogramComponent {
  readonly bins = input.required<HistogramBin[]>();
  readonly markers_ = input<ThresholdMarker[]>([], { alias: 'markers' });
  readonly label = input('Distribution');
  readonly unit = input('transactions');

  readonly height = HEIGHT;
  readonly pad = PAD;
  readonly width = signal(0);
  readonly hover = signal<number | null>(null);

  constructor() {
    const host = inject(ElementRef<HTMLElement>).nativeElement as HTMLElement;
    const destroyRef = inject(DestroyRef);
    afterNextRender(() => {
      this.width.set(host.clientWidth);
      const observer = new ResizeObserver(() => this.width.set(host.clientWidth));
      observer.observe(host);
      destroyRef.onDestroy(() => observer.disconnect());
    });
  }

  readonly plotHeight = computed(() => HEIGHT - PAD.top - PAD.bottom);
  private readonly domain = computed(() => {
    const b = this.bins();
    return { min: b.length ? b[0].from : 0, max: b.length ? b[b.length - 1].to : 1 };
  });
  private readonly yMax = computed(() => niceMax(Math.max(0, ...this.bins().map((b) => b.count))));

  private x(value: number): number {
    const { min, max } = this.domain();
    return PAD.left + ((value - min) / (max - min || 1)) * (this.width() - PAD.left - PAD.right);
  }

  private y(value: number): number {
    return PAD.top + this.plotHeight() * (1 - value / this.yMax());
  }

  readonly ticks = computed(() =>
    [0, 0.5, 1].map((f) => ({ value: f * this.yMax(), y: this.y(f * this.yMax()), text: compact(f * this.yMax()) })),
  );

  readonly bars = computed(() =>
    this.bins().map((b, i) => {
      const x0 = this.x(b.from) + 1;
      const x1 = this.x(b.to) - 1;
      const w = Math.max(x1 - x0, 1);
      const top = this.y(b.count);
      const base = this.y(0);
      const r = Math.min(4, w / 2, base - top);
      // Rounded top corners only; the bar stays anchored to the baseline.
      const d = b.count === 0 ? '' : `M${x0},${base} V${top + r} Q${x0},${top} ${x0 + r},${top} H${x0 + w - r} `
        + `Q${x0 + w},${top} ${x0 + w},${top + r} V${base} Z`;
      return { i, x: x0 - 1, w: w + 2, d };
    }),
  );

  readonly xLabels = computed(() => {
    const b = this.bins();
    const step = Math.max(1, Math.ceil(b.length / Math.max(2, Math.floor(this.width() / 70))));
    return b.filter((_, i) => i % step === 0).map((bin) => ({ x: this.x(bin.from), text: bin.from.toFixed(2) }));
  });

  readonly markers = computed(() => this.markers_().map((m) => ({ ...m, x: this.x(m.value) })));

  readonly hoverBin = computed(() => {
    const i = this.hover();
    if (i === null || !this.bins()[i]) {
      return null;
    }
    const bin = this.bins()[i];
    const left = Math.min(Math.max(this.x((bin.from + bin.to) / 2), 70), this.width() - 70);
    return { bin, left };
  });
}
