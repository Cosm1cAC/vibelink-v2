/** The Hub ledger rolls over at UTC+8, regardless of this computer's timezone. */
export function utc8Day(now = Date.now()): string {
  return new Date(now + 8 * 60 * 60 * 1000).toISOString().slice(0, 10);
}
