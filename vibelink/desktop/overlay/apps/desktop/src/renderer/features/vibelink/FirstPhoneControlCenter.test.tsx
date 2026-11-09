// @vitest-environment jsdom
import React from 'react';
import { act, cleanup, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import type { HubSession } from '../../../shared/vibelinkAuth';
import { VibeLinkControlCenter } from './VibeLinkControlCenter';
import {
  armFirstPhonePairing,
  beginFirstPhonePairing,
  cancelFirstPhonePairing,
  markFirstPhoneNavigation,
} from './firstPhonePairing';
const state = vi.hoisted(() => ({ session: null as HubSession | null }));
vi.mock('./useHubAuth', () => ({ useHubAuth: () => ({ snapshot: { session: state.session } }) }));
vi.mock('./desktopBridge', () => ({
  requestVibeLink: vi.fn(() => new Promise(() => {})),
}));
vi.mock('./HubPairQr', () => ({ HubPairQr: () => <div>Fixture 手机连接码</div> }));
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
  state.session = null;
  window.location.hash = '';
});
it('opens the existing mobile modal only once for the matching handoff and closes it on session change', () => {
  const token = beginFirstPhonePairing();
  armFirstPhonePairing(token, owner);
  markFirstPhoneNavigation(token, owner);
  window.location.hash = `/vibelink?firstPhone=${token}`;
  state.session = owner;
  const view = render(<VibeLinkControlCenter embedded />);
  expect(screen.getByText('Fixture 手机连接码')).toBeTruthy();
  expect(window.location.hash).toBe('#/vibelink');
  state.session = { ...owner, sessionId: 'other' };
  view.rerender(<VibeLinkControlCenter embedded />);
  expect(screen.queryByText('Fixture 手机连接码')).toBeNull();
});
it('does not treat a URL hint alone or a changed identity as permission to pair', () => {
  window.location.hash = '/vibelink?firstPhone=forged';
  state.session = owner;
  const view = render(<VibeLinkControlCenter embedded />);
  expect(screen.queryByText('Fixture 手机连接码')).toBeNull();
  act(() => {
    const token = beginFirstPhonePairing();
    armFirstPhonePairing(token, owner);
    markFirstPhoneNavigation(token, owner);
    window.location.hash = `/vibelink?firstPhone=${token}`;
  });
  state.session = { ...owner, edition: 'beta' };
  view.rerender(<VibeLinkControlCenter embedded />);
  expect(screen.queryByText('Fixture 手机连接码')).toBeNull();
});
