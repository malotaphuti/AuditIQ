import { Pipe, PipeTransform } from '@angular/core';

/** LOGIN_SUCCESS -> "Login success"; DUPLICATE_PAYMENT -> "Duplicate payment". */
@Pipe({ name: 'humanize' })
export class HumanizePipe implements PipeTransform {
  transform(value: string | null | undefined): string {
    if (!value) {
      return '';
    }
    const words = value.replace(/[_-]+/g, ' ').trim().toLowerCase();
    return words.charAt(0).toUpperCase() + words.slice(1);
  }
}
