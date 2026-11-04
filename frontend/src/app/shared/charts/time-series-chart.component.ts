import { DecimalPipe } from '@angular/common';
import {
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';

export interface SeriesPoint {
  /** ISO date (YYYY-MM-DD) or month (YYYY-MM). */
  key: string;
  value: number;
  /** Optional second measure shown only in the tooltip (never on a second axis). */
  secondary?: number;
}

const HEIGHT = 220;
const PAD = { top: 12, right: 12, bottom: 28, left: 56 };

/** Rounds the axis maximum up to a "nice" number so gridlines land on readable values. */
export function niceMax(max: number): number {
  if (max <= 0) {
    return 1;
  }
  const exp = Math.pow(10, Math.floor(Math.log10(max)));
  const f = max / exp;
  const nice = f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10;
  return nice * exp;
}

export function compact(n: number): string {
  const abs = Math.abs(n);
  if (abs >= 1e9) return `${+(n / 1e9).toFixed(1)}B`;
  if (abs >= 1e6) return `${+(n / 1e6).toFixed(1)}M`;
  if (abs >= 1e3) return `${+(n / 1e3).toFixed(1)}k`;
  return `${+n.toFixed(0)}`;
}

/**
 * Single-series time chart (columns or line+area) rendered as SVG at the host's real pixel width.
 * Hover shows a crosshair and tooltip; a visually hidden table carries the same data for assistive technology.
 */
@Component({
  selector: 'app-time-series-chart',
  imports: [DecimalPipe],
  template: `
    <div class="chart" (mouseleave)="hover.set(null)">
      @if (width() > 0 && data().length > 0) {
        <svg
          [attr.width]="width()"
          [attr.height]="height"
          role="img"
          [attr.aria-label]="label()"
          (mousemove)="onMove($event)"
        >
          @for (t of ticks(); track t.value) {
            <line class="grid" [attr.x1]="pad.left" [attr.x2]="width() - pad.right" [attr.y1]="t.y" [attr.y2]="t.y" />
            <text class="axis" [attr.x]="pad.left - 8" [attr.y]="t.y + 4" text-anchor="end">{{ t.text }}</text>
          }
          @for (x of xLabels(); track x.i) {
            <text class="axis" [attr.x]="x.x" [attr.y]="height - 8" text-anchor="middle">{{ x.text }}</text>
          }

          @if (kind() === 'bar') {
            @for (b of bars(); track b.i) {
              <path class="mark" [class.dim]="hover() !== null && hover() !== b.i" [attr.d]="b.d" />
            }
          } @else {
            <path class="area" [attr.d]="areaPath()" />
            <path class="line" [attr.d]="linePath()" />
          }

          @if (hoverPoint(); as h) {
            <line class="crosshair" [attr.x1]="h.x" [attr.x2]="h.x" [attr.y1]="pad.top" [attr.y2]="height - pad.bottom" />
            @if (kind() === 'line') {
              <circle class="dot" [attr.cx]="h.x" [attr.cy]="h.y" r="4" />
            }
          }
        </svg>
        @if (hoverPoint(); as h) {
          <div class="tooltip" [style.left.px]="h.tipX" role="status">
            <div class="tip-key">{{ h.point.key }}</div>
            <div class="tip-row"><span>{{ valueLabel() }}</span><strong>{{ h.point.value | number: '1.0-0' }}</strong></div>
            @if (h.point.secondary !== undefined && secondaryLabel()) {
              <div class="tip-row"><span>{{ secondaryLabel() }}</span><strong>{{ h.point.secondary | number: '1.0-0' }}</strong></div>
            }
          </div>
        }
      }
      <table class="cdk-visually-hidden">
        <caption>{{ label() }}</caption>
        <tr><th scope="col">Period</th><th scope="col">{{ valueLabel() }}</th>
          @if (secondaryLabel()) { <th scope="col">{{ secondaryLabel() }}</th> }</tr>
        @for (p of data(); track p.key) {
          <tr><td>{{ p.key }}</td><td>{{ p.value }}</td>@if (secondaryLabel()) { <td>{{ p.secondary }}</td> }</tr>
        }
      </table>
    </div>
  `,
  styles: `
    :host { display: block; }
    .chart { position: relative; }
    svg { display: block; overflow: visible; }
    .grid { stroke: var(--aiq-border); stroke-width: 1; }
    .axis { fill: var(--aiq-text-muted); font-size: 11px; font-variant-numeric: tabular-nums; }
    .mark { fill: var(--aiq-series-1); transition: opacity 120ms; }
    .mark.dim { opacity: 0.45; }
    .line { fill: none; stroke: var(--aiq-series-1); stroke-width: 2; stroke-linejoin: round; }
    .area { fill: var(--aiq-series-1); opacity: 0.1; }
    .crosshair { stroke: var(--aiq-text-muted); stroke-width: 1; stroke-dasharray: 3 3; }
    .dot { fill: var(--aiq-series-1); stroke: var(--aiq-card); stroke-width: 2; }
    .tooltip {
      position: absolute; top: 0; transform: translateX(-50%); pointer-events: none;
      background: var(--aiq-card); border: 1px solid var(--aiq-border); border-radius: 8px;
      box-shadow: 0 4px 16px rgba(11, 31, 58, 0.12); padding: 8px 10px; font-size: 12px; min-width: 140px;
      color: var(--aiq-text);
    }
    .tip-key { font-weight: 600; margin-bottom: 4px; }
    .tip-row { display: flex; justify-content: space-between; gap: 12px; color: var(--aiq-text-muted); }
    .tip-row strong { color: var(--aiq-text); font-variant-numeric: tabular-nums; }
  `,
})
export class TimeSeriesChartComponent {
  readonly data = input.required<SeriesPoint[]>();
  readonly kind = input<'bar' | 'line'>('line');
  readonly label = input('Time series');
  readonly valueLabel = input('Value');
  readonly secondaryLabel = input<string>();

  readonly height = HEIGHT;
  readonly pad = PAD;
  readonly width = signal(0);
  readonly hover = signal<number | null>(null);

  constructor() {
    const host = inject(ElementRef<HTMLElement>).nativeElement as HTMLElement;
    const destroyRef = inject(DestroyRef);
    afterNextRender(() => {
      this.width.set(host.clientWidth);
      if (typeof ResizeObserver !== 'undefined') {
        const observer = new ResizeObserver((entries) => this.width.set(entries[0].contentRect.width));
        observer.observe(host);
        destroyRef.onDestroy(() => observer.disconnect());
      }
    });
  }

  private readonly yMax = computed(() => niceMax(Math.max(0, ...this.data().map((p) => p.value))));
  private readonly plotW = computed(() => Math.max(0, this.width() - PAD.left - PAD.right));
  private readonly plotH = HEIGHT - PAD.top - PAD.bottom;

  private y(v: number): number {
    return PAD.top + this.plotH - (v / this.yMax()) * this.plotH;
  }

  /** Band centre for bars, evenly spaced points for lines. */
  private x(i: number): number {
    const n = this.data().length;
    if (this.kind() === 'bar') {
      return PAD.left + (i + 0.5) * (this.plotW() / n);
    }
    return PAD.left + (n === 1 ? this.plotW() / 2 : (i / (n - 1)) * this.plotW());
  }

  readonly ticks = computed(() =>
    [0, 0.25, 0.5, 0.75, 1].map((f) => ({ value: f, y: this.y(f * this.yMax()), text: compact(f * this.yMax()) })),
  );

  readonly xLabels = computed(() => {
    const n = this.data().length;
    const every = Math.max(1, Math.ceil(n / Math.max(2, Math.floor(this.plotW() / 80))));
    return this.data()
      .map((p, i) => ({ i, x: this.x(i), text: p.key.length === 7 ? p.key : p.key.slice(5) }))
      .filter((p) => p.i % every === 0);
  });

  readonly bars = computed(() => {
    const n = this.data().length;
    const band = this.plotW() / Math.max(n, 1);
    const w = Math.max(2, Math.min(band - 2, band * 0.7));
    const base = this.y(0);
    return this.data().map((p, i) => {
      const x = this.x(i) - w / 2;
      const top = this.y(p.value);
      const r = Math.min(4, w / 2, base - top);
      // Rounded data end, square baseline.
      const d = base - top < 0.5 ? '' :
        `M${x},${base} V${top + r} Q${x},${top} ${x + r},${top} H${x + w - r} Q${x + w},${top} ${x + w},${top + r} V${base} Z`;
      return { i, d };
    });
  });

  readonly linePath = computed(() =>
    this.data().map((p, i) => `${i === 0 ? 'M' : 'L'}${this.x(i)},${this.y(p.value)}`).join(' '),
  );

  readonly areaPath = computed(() => {
    const n = this.data().length;
    if (n === 0) return '';
    return `${this.linePath()} L${this.x(n - 1)},${this.y(0)} L${this.x(0)},${this.y(0)} Z`;
  });

  readonly hoverPoint = computed(() => {
    const i = this.hover();
    if (i === null || !this.data()[i]) return null;
    const x = this.x(i);
    return {
      point: this.data()[i],
      x,
      y: this.y(this.data()[i].value),
      tipX: Math.min(Math.max(x, 80), this.width() - 80),
    };
  });

  onMove(event: MouseEvent): void {
    const n = this.data().length;
    if (n === 0 || this.plotW() <= 0) return;
    const rel = event.offsetX - PAD.left;
    const i = this.kind() === 'bar'
      ? Math.floor(rel / (this.plotW() / n))
      : Math.round((rel / this.plotW()) * (n - 1));
    this.hover.set(Math.min(n - 1, Math.max(0, i)));
  }
}
