import { expect, it } from 'vitest';
import { notificationRoute } from './notificationNavigation';
it('opens a fixed Hub route only for the same current verified principal', () => {
  const target = {
    issuer: 'hub_a',
    edition: 'dev' as const,
    userId: 'A',
    groupId: 'g',
    workId: 'w',
  };
  expect(notificationRoute(target, { ...target })).toBe('/hub?group=g&work=w');
  expect(notificationRoute(target, { ...target, userId: 'B' })).toBeNull();
  expect(notificationRoute({ ...target, groupId: 'https://evil.invalid' }, target)).toBeNull();
  expect(notificationRoute(target, null)).toBeNull();
});
