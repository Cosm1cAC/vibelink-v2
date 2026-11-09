import { afterEach, expect, it, vi } from 'vitest';
import {
  beginFirstPhonePairing,
  armFirstPhonePairing,
  markFirstPhoneNavigation,
  takeFirstPhonePairing,
  cancelFirstPhonePairing,
  getFirstPhonePairing,
} from './firstPhonePairing';
import type { HubSession } from '../../../shared/vibelinkAuth';
const owner: HubSession = {
  userId: 'owner',
  label: 'Local',
  tenantId: null,
  edition: 'dev',
  sessionId: 's1',
  expiresAt: Date.now() + 60000,
  issuer: 'hub_123456789012345678901234',
  hubId: 'hub_123456789012345678901234',
  permissions: ['client'],
};
afterEach(() => {
  cancelFirstPhonePairing();
  vi.useRealTimers();
});
it('opens a pairing handoff once only after the exact verified dev owner session', () => {
  const token = beginFirstPhonePairing();
  expect(armFirstPhonePairing(token, owner)).toBe(true);
  expect(markFirstPhoneNavigation(token, { ...owner, sessionId: 'other' })).toBe(false);
  expect(markFirstPhoneNavigation(token, owner)).toBe(true);
  expect(takeFirstPhonePairing(token, owner)).toBe(true);
  expect(takeFirstPhonePairing(token, owner)).toBe(false);
  expect(getFirstPhonePairing()).toBeNull();
});
it('rejects beta, tenant and expired identities, and a changed issuer', () => {
  const token = beginFirstPhonePairing();
  expect(armFirstPhonePairing(token, { ...owner, edition: 'beta' })).toBe(false);
  expect(armFirstPhonePairing(token, { ...owner, tenantId: 'tenant' })).toBe(false);
  expect(armFirstPhonePairing(token, { ...owner, expiresAt: 0 })).toBe(false);
  expect(armFirstPhonePairing(token, owner)).toBe(true);
  expect(markFirstPhoneNavigation(token, { ...owner, issuer: 'other' })).toBe(false);
});
it('cannot resurrect a cancelled or superseded request and expires unused hints', () => {
  vi.useFakeTimers();
  const old = beginFirstPhonePairing();
  cancelFirstPhonePairing(old);
  expect(armFirstPhonePairing(old, owner)).toBe(false);
  const next = beginFirstPhonePairing();
  cancelFirstPhonePairing(old);
  expect(getFirstPhonePairing()?.token).toBe(next);
  expect(armFirstPhonePairing(old, owner)).toBe(false);
  vi.advanceTimersByTime(60001);
  expect(armFirstPhonePairing(next, { ...owner, expiresAt: Date.now() + 60000 })).toBe(false);
});
