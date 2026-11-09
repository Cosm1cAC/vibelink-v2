// @vitest-environment jsdom
import React from 'react';
import { act, cleanup, render, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import type { HubSession } from '../../../shared/vibelinkAuth';
import { FirstPhonePairingNavigation } from './FirstPhonePairingNavigation';
import {
  armFirstPhonePairing,
  beginFirstPhonePairing,
  cancelFirstPhonePairing,
  getFirstPhonePairing,
} from './firstPhonePairing';
const state = vi.hoisted(() => ({
  session: null as HubSession | null,
  mode: 'hub',
  dataOwnerId: 'local-v1',
  isInitializing: false,
}));
vi.mock('./useHubAuth', () => ({ useHubAuth: () => ({ snapshot: { session: state.session } }) }));
vi.mock('@/contexts/AuthContext', () => ({ useAuth: () => state }));
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
  cleanup();
  cancelFirstPhonePairing();
  window.location.hash = '';
  state.session = null;
  state.mode = 'hub';
  state.dataOwnerId = 'local-v1';
  state.isInitializing = false;
});
it('waits for committed local owner state before navigating the exact session', async () => {
  const token = beginFirstPhonePairing();
  armFirstPhonePairing(token, owner);
  state.session = owner;
  state.isInitializing = true;
  const view = render(<FirstPhonePairingNavigation />);
  expect(window.location.hash).toBe('');
  state.isInitializing = false;
  view.rerender(<FirstPhonePairingNavigation />);
  await waitFor(() => expect(window.location.hash).toBe(`#/vibelink?firstPhone=${token}`));
  expect(getFirstPhonePairing()?.phase).toBe('navigated');
});
it('cancels a handoff on an observed account switch and never navigates a nonlocal owner', () => {
  const token = beginFirstPhonePairing();
  armFirstPhonePairing(token, owner);
  state.session = owner;
  state.dataOwnerId = 'another-owner';
  const view = render(<FirstPhonePairingNavigation />);
  expect(window.location.hash).toBe('');
  state.session = { ...owner, sessionId: 'new-login' };
  view.rerender(<FirstPhonePairingNavigation />);
  expect(getFirstPhonePairing()).toBeNull();
  expect(window.location.hash).toBe('');
});
it('does not navigate a cancelled request when a late authenticated snapshot arrives', () => {
  const token = beginFirstPhonePairing();
  armFirstPhonePairing(token, owner);
  const view = render(<FirstPhonePairingNavigation />);
  act(() => cancelFirstPhonePairing(token));
  state.session = owner;
  view.rerender(<FirstPhonePairingNavigation />);
  expect(window.location.hash).toBe('');
});
