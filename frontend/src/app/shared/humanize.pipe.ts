import { Pipe, PipeTransform } from '@angular/core';

/** Codes that must stay upper-case when humanised. */
const ACRONYMS = new Set(['POS', 'ATM', 'EFT', 'IT', 'ZAR', 'ID', 'ML', 'AI', 'CSV']);

/** LOGIN_SUCCESS -> "Login success"; IT_SERVICES -> "IT services"; POS -> "POS". */
@Pipe({ name: 'humanize' })
export class HumanizePipe implements PipeTransform {
  transform(value: string | null | undefined): string {
    if (!value) {
      return '';
    }
    const words = value.replace(/[_-]+/g, ' ').trim().split(/\s+/);
    return words
      .map((w, i) => {
        const upper = w.toUpperCase();
        if (ACRONYMS.has(upper)) {
          return upper;
        }
        const lower = w.toLowerCase();
        return i === 0 ? lower.charAt(0).toUpperCase() + lower.slice(1) : lower;
      })
      .join(' ');
  }
}
