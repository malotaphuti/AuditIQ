/** Turns a JSON evidence object into label/value pairs for display. Keys are humanised; nested objects skipped. */
export interface Fact {
  label: string;
  value: string;
}

const ISO_TIMESTAMP = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/;

export function humanizeKey(key: string): string {
  const words = key
    .replace(/_/g, ' ')
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .toLowerCase()
    .trim();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

export function formatFactValue(value: unknown): string {
  if (value === null || value === undefined || value === '') {
    return '—';
  }
  if (Array.isArray(value)) {
    return value.map((v) => formatFactValue(v)).join(', ');
  }
  if (typeof value === 'boolean') {
    return value ? 'Yes' : 'No';
  }
  if (typeof value === 'number') {
    return Number.isInteger(value) ? value.toLocaleString('en-US') : value.toLocaleString('en-US', {
      maximumFractionDigits: 2,
    });
  }
  if (typeof value === 'string' && ISO_TIMESTAMP.test(value)) {
    const date = new Date(value);
    if (!Number.isNaN(date.getTime())) {
      return date.toLocaleString('en-US', { dateStyle: 'medium', timeStyle: 'short' });
    }
  }
  return String(value);
}

export function toFacts(source: Record<string, unknown> | null | undefined, exclude: string[] = []): Fact[] {
  if (!source) {
    return [];
  }
  return Object.entries(source)
    .filter(([key, value]) => !exclude.includes(key) && (value === null || typeof value !== 'object'
      || Array.isArray(value)))
    .map(([key, value]) => ({ label: humanizeKey(key), value: formatFactValue(value) }));
}
