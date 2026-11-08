import { expect, it } from 'vitest';
import { workActions } from './workPresentation';
it('does not offer management for records without server permission, including owner sessions', () => {
  expect(workActions({ status: 'failed', retryable: true, archived: false })).toEqual([]);
});

it('allows only explicit server-authorized recovery and does not offer another cancel for unknown termination', () => {
  expect(
    workActions({ canManage: true, status: 'failed', retryable: true, archived: false }),
  ).toEqual(['retry', 'archive']);
  expect(
    workActions({
      canManage: true,
      status: 'cancelled_unknown',
      retryable: false,
      archived: false,
    }),
  ).toEqual([]);
});
