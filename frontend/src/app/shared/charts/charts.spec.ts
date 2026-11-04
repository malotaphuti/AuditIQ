import { TestBed } from '@angular/core/testing';
import { DurationPipe } from '../duration.pipe';
import { BarListComponent } from './bar-list.component';
import { TimeSeriesChartComponent, compact, niceMax } from './time-series-chart.component';

describe('chart helpers', () => {
  it('rounds axis maxima to readable values', () => {
    expect(niceMax(0)).toBe(1);
    expect(niceMax(87)).toBe(100);
    expect(niceMax(1234)).toBe(2000);
    expect(niceMax(2300)).toBe(2500);
    expect(niceMax(4_100_000)).toBe(5_000_000);
  });

  it('formats compact numbers', () => {
    expect(compact(950)).toBe('950');
    expect(compact(12_500)).toBe('12.5k');
    expect(compact(3_000_000)).toBe('3M');
  });

  it('formats durations', () => {
    const p = new DurationPipe();
    expect(p.transform(850)).toBe('850 ms');
    expect(p.transform(12_400)).toBe('12.4 s');
    expect(p.transform(125_000)).toBe('2 min 5 s');
    expect(p.transform(undefined)).toBe('—');
  });
});

describe('BarListComponent', () => {
  it('ranks by the chosen measure and scales to the largest bar', () => {
    const fixture = TestBed.createComponent(BarListComponent);
    fixture.componentRef.setInput('items', [
      { key: 'A', count: 10, value: 100 },
      { key: 'B', count: 5, value: 400 },
    ]);
    fixture.detectChanges();
    expect(fixture.componentInstance.rows().map((r) => r.key)).toEqual(['A', 'B']);
    expect(fixture.componentInstance.rows()[1].pct).toBe(50);

    fixture.componentRef.setInput('measure', 'value');
    fixture.detectChanges();
    expect(fixture.componentInstance.rows().map((r) => r.key)).toEqual(['B', 'A']);
  });
});

describe('TimeSeriesChartComponent', () => {
  it('draws one mark per bucket and exposes an accessible table', () => {
    const fixture = TestBed.createComponent(TimeSeriesChartComponent);
    fixture.componentRef.setInput('data', [
      { key: '2026-01', value: 10 }, { key: '2026-02', value: 20 }, { key: '2026-03', value: 0 },
    ]);
    fixture.componentRef.setInput('kind', 'bar');
    fixture.componentInstance.width.set(600);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    // Zero-height buckets draw no path.
    expect(el.querySelectorAll('path.mark[d^="M"]').length).toBe(2);
    expect(el.querySelectorAll('table tr').length).toBe(4);
  });

  it('shows a tooltip for the hovered bucket', () => {
    const fixture = TestBed.createComponent(TimeSeriesChartComponent);
    fixture.componentRef.setInput('data', [{ key: '2026-01-01', value: 5, secondary: 900 }]);
    fixture.componentRef.setInput('secondaryLabel', 'Value');
    fixture.componentInstance.width.set(400);
    fixture.componentInstance.hover.set(0);
    fixture.detectChanges();
    const tip: HTMLElement = fixture.nativeElement.querySelector('.tooltip');
    expect(tip.textContent).toContain('2026-01-01');
    expect(tip.textContent).toContain('900');
  });
});
