import { CurrencyPipe, DecimalPipe } from '@angular/common';
import { Component, computed, input } from '@angular/core';
import { HumanizePipe } from '../humanize.pipe';

export interface BarItem {
  key: string;
  count: number;
  value: number;
}

/**
 * Ranked horizontal bars (magnitude by category) in HTML so labels stay crisp at any width.
 * Length encodes the chosen measure; both count and ZAR value are written as direct labels.
 */
@Component({
  selector: 'app-bar-list',
  imports: [DecimalPipe, CurrencyPipe, HumanizePipe],
  template: `
    <ul class="bars" [attr.aria-label]="label()">
      @for (item of rows(); track item.key) {
        <li [title]="(humanizeKeys() ? (item.key | humanize) : item.key) + ': ' + (item.count | number)">
          <span class="name">{{ humanizeKeys() ? (item.key | humanize) : item.key }}</span>
          <span class="track" aria-hidden="true"><span class="fill" [style.width.%]="item.pct"></span></span>
          <span class="val">
            @if (measure() === 'value') {
              {{ item.value | currency: 'ZAR' : 'symbol-narrow' : '1.0-0' }}
            } @else {
              {{ item.count | number }}
            }
          </span>
        </li>
      }
    </ul>
  `,
  styles: `
    .bars { list-style: none; margin: 0; padding: 0; display: grid; gap: 6px; }
    li {
      display: grid; grid-template-columns: minmax(90px, 34%) 1fr auto; align-items: center; gap: 10px;
      font-size: 0.85rem; padding: 2px 4px; border-radius: 6px;
    }
    li:hover { background: var(--aiq-surface); }
    .name { color: var(--aiq-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .track { height: 10px; background: transparent; border-radius: 0 4px 4px 0; }
    .fill { display: block; height: 100%; min-width: 2px; background: var(--aiq-series-1); border-radius: 0 4px 4px 0; }
    .val { color: var(--aiq-text-muted); font-variant-numeric: tabular-nums; text-align: right; min-width: 72px; }
  `,
})
export class BarListComponent {
  readonly items = input.required<BarItem[]>();
  readonly measure = input<'count' | 'value'>('count');
  readonly label = input('Breakdown');
  readonly limit = input(10);
  /** Turn enum-style codes into words; disable for proper nouns such as city names. */
  readonly humanizeKeys = input(true);

  readonly rows = computed(() => {
    const m = this.measure();
    const sorted = [...this.items()].sort((a, b) => (m === 'value' ? b.value - a.value : b.count - a.count));
    const top = sorted.slice(0, this.limit());
    const max = Math.max(1, ...top.map((i) => (m === 'value' ? i.value : i.count)));
    return top.map((i) => ({ ...i, pct: ((m === 'value' ? i.value : i.count) / max) * 100 }));
  });
}
