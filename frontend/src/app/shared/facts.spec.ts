import { formatFactValue, humanizeKey, toFacts } from './facts';

describe('facts', () => {
  it('humanises camelCase and snake_case keys', () => {
    expect(humanizeKey('matchedTransactionId')).toBe('Matched transaction id');
    expect(humanizeKey('window_hours')).toBe('Window hours');
  });

  it('formats values for display', () => {
    expect(formatFactValue(null)).toBe('—');
    expect(formatFactValue(true)).toBe('Yes');
    expect(formatFactValue(['A', 'B'])).toBe('A, B');
    expect(formatFactValue(1234567)).toContain('234');
    expect(formatFactValue('plain')).toBe('plain');
  });

  it('skips excluded keys and nested objects', () => {
    expect(toFacts({ a: 1, thresholds: { x: 1 }, skip: 'x', list: ['p'] }, ['skip'])).toEqual([
      { label: 'A', value: '1' },
      { label: 'List', value: 'p' },
    ]);
    expect(toFacts(undefined)).toEqual([]);
  });
});
