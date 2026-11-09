import { expect, it } from 'vitest';
import { utc8Day } from './adminPresentation';
it('selects the Hub usage day at the UTC+8 rollover', () => {
  expect(utc8Day(Date.parse('2026-11-09T15:59:59Z'))).toBe('2026-11-09');
  expect(utc8Day(Date.parse('2026-11-09T16:00:00Z'))).toBe('2026-11-10');
});
