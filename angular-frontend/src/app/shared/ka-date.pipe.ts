import { Pipe, PipeTransform } from '@angular/core';
import { formatKaDate, formatKaDateTime, formatKaDateTimeSeconds, formatKaDayMonth } from './ka-date';

export type KaDateShape = 'date' | 'datetime' | 'seconds' | 'daymonth';

/**
 * The template's way into ka-date.ts. It replaces Angular's DatePipe, whose
 * free-form format string is how six screens came to show a date six ways
 * (`dd.MM.yyyy`, `dd.MM.yyyy HH:mm`, ...). The shapes here are the only ones.
 */
@Pipe({ name: 'kaDate', standalone: true })
export class KaDatePipe implements PipeTransform {
  transform(value: string | null | undefined, shape: KaDateShape = 'date'): string {
    if (!value) return '—';
    switch (shape) {
      case 'datetime':
        return formatKaDateTime(value);
      case 'seconds':
        return formatKaDateTimeSeconds(value);
      case 'daymonth':
        return formatKaDayMonth(value);
      default:
        return formatKaDate(value);
    }
  }
}
