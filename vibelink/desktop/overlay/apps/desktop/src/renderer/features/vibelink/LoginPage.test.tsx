// @vitest-environment jsdom
// Protects the real unified login form: beta activation is distinct from explicit
// local history access, and an invite never remains in UI state after submission.
import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { VibeLinkLoginPage } from './LoginPage';
import { cancelFirstPhonePairing, getFirstPhonePairing } from './firstPhonePairing';
import type { HubAuthResult, HubSession } from '../../../shared/vibelinkAuth';
const owner: HubSession = {
  userId: 'owner',
  label: 'Local',
  tenantId: null,
  edition: 'dev',
  sessionId: 'first-phone',
  expiresAt: Date.now() + 60000,
  issuer: 'hub_123456789012345678901234',
  hubId: 'hub_123456789012345678901234',
  permissions: ['client'],
};
const signedOut: HubAuthResult = {
  success: true,
  data: { status: 'signed_out', session: null, qr: null },
};
const authenticated = (session = owner): HubAuthResult => ({
  success: true,
  data: { status: 'authenticated', session, qr: null },
});
function bridge(auth: ReturnType<typeof vi.fn>) {
  return {
    enabled: true,
    auth,
    work: vi.fn(),
    notifications: vi.fn(),
    onNotificationOpen: () => () => {},
    onAuthState: () => () => {},
    request: vi.fn(),
  };
}
afterEach(() => {
  cleanup();
  cancelFirstPhonePairing();
});
vi.mock('@/components/title-bar/WindowControls', () => ({ WindowControls: () => null }));
vi.mock('react-i18next', async () => {
  const resources = (await import('../../i18n/locales/zh-CN/common.json')).default;
  return {
    useTranslation: () => ({
      t: (key: string) =>
        key
          .split('.')
          .reduce<unknown>(
            (value, part) => (value as Record<string, unknown>)?.[part],
            resources,
          ) ?? key,
    }),
  };
});
it('only verifies the local workspace after the explicit first-phone click', async () => {
  const auth = vi.fn().mockResolvedValueOnce(signedOut).mockResolvedValue(authenticated());
  render(<VibeLinkLoginPage bridge={bridge(auth)} />);
  await waitFor(() => expect(auth).toHaveBeenCalledTimes(1));
  expect(auth).not.toHaveBeenCalledWith({ action: 'useLocalWorkspace' });
  fireEvent.click(screen.getByRole('button', { name: '扫码登录' }));
  await waitFor(() => expect(getFirstPhonePairing()?.phase).toBe('ready'));
  expect(auth.mock.calls.map((call) => call[0].action)).toEqual([
    'snapshot',
    'useLocalWorkspace',
    'snapshot',
  ]);
  expect(getFirstPhonePairing()?.session?.sessionId).toBe(owner.sessionId);
});
it.each(['failure', 'beta', 'switched'] as const)(
  'does not arm phone setup after %s',
  async (kind) => {
    const login =
      kind === 'failure'
        ? {
            success: false,
            error: { code: 'LOGIN_FAILED', message: 'not ready', recoverable: true },
          }
        : authenticated(kind === 'beta' ? { ...owner, edition: 'beta' } : owner);
    const auth = vi
      .fn()
      .mockResolvedValueOnce(signedOut)
      .mockResolvedValueOnce(login)
      .mockResolvedValue(authenticated({ ...owner, sessionId: 'changed' }));
    render(<VibeLinkLoginPage bridge={bridge(auth)} />);
    await waitFor(() => expect(auth).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole('button', { name: '扫码登录' }));
    await waitFor(() => expect(auth).toHaveBeenCalledWith({ action: 'useLocalWorkspace' }));
    await waitFor(() => expect(getFirstPhonePairing()).toBeNull());
  },
);
it('cancels an in-flight owner verification and ignores its late response', async () => {
  let finish!: (value: HubAuthResult) => void;
  const pending = new Promise<HubAuthResult>((resolve) => {
    finish = resolve;
  });
  const auth = vi
    .fn()
    .mockResolvedValueOnce(signedOut)
    .mockImplementation((input) =>
      input.action === 'useLocalWorkspace' ? pending : Promise.resolve(signedOut),
    );
  render(<VibeLinkLoginPage bridge={bridge(auth)} />);
  await waitFor(() => expect(auth).toHaveBeenCalledTimes(1));
  fireEvent.click(screen.getByRole('button', { name: '扫码登录' }));
  fireEvent.click(await screen.findByRole('button', { name: '取消扫码登录' }));
  await waitFor(() => expect(auth).toHaveBeenCalledWith({ action: 'logout' }));
  await act(async () => finish(authenticated()));
  await waitFor(() => expect(getFirstPhonePairing()).toBeNull());
  expect(auth.mock.calls.filter((call) => call[0].action === 'snapshot')).toHaveLength(1);
});
it('hides the first-phone bootstrap in the beta connection mode', async () => {
  const auth = vi.fn().mockResolvedValue(signedOut);
  render(<VibeLinkLoginPage bridge={bridge(auth)} />);
  await waitFor(() => expect(auth).toHaveBeenCalledTimes(1));
  await act(async () =>
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'beta' } }),
  );
  expect(screen.queryByRole('button', { name: '扫码登录' })).toBeNull();
  expect(auth).not.toHaveBeenCalledWith({ action: 'useLocalWorkspace' });
});

it('offers local history explicitly and clears a submitted beta code', async () => {
  const auth = vi.fn(async () => ({
    success: true as const,
    data: { status: 'signed_out' as const, session: null, qr: null },
  }));
  const bridge = {
    enabled: true,
    auth,
    work: vi.fn(),
    notifications: vi.fn(),
    onNotificationOpen: () => () => {},
    onAuthState: () => () => undefined,
    request: vi.fn(),
  };
  render(<VibeLinkLoginPage bridge={bridge} />);
  expect(screen.getByRole('button', { name: '仅打开电脑工作区' })).toBeTruthy();
  fireEvent.change(screen.getByLabelText('内测码'), { target: { value: 'fake-invite' } });
  fireEvent.click(screen.getByRole('button', { name: '使用内测码登录' }));
  await waitFor(() =>
    expect(auth).toHaveBeenCalledWith({ action: 'activateBeta', code: 'fake-invite' }),
  );
  await waitFor(() => expect((screen.getByLabelText('内测码') as HTMLInputElement).value).toBe(''));
  expect(screen.queryByText('登录 Cindy')).toBeNull();
});

it('hides old dev desktop-login QR and makes the sole scan action verify the local owner', async () => {
  const legacy: HubAuthResult = {
    success: true,
    data: {
      status: 'signed_out',
      session: null,
      qr: {
        edition: 'dev',
        link: 'vibelink://login?purpose=desktop_login&id=legacy',
        verificationCode: '12 34',
        expiresAt: Date.now() + 60000,
        status: 'pending',
      },
    },
  };
  const auth = vi.fn().mockResolvedValueOnce(legacy).mockResolvedValue(authenticated());
  render(<VibeLinkLoginPage bridge={bridge(auth)} />);
  await waitFor(() => expect(auth).toHaveBeenCalledTimes(1));
  expect(screen.queryAllByRole('img')).toHaveLength(0);
  expect(screen.queryByText('12 34')).toBeNull();
  expect(screen.getAllByRole('button', { name: '扫码登录' })).toHaveLength(1);
  expect(screen.getByText('打开本机工作区，并显示手机登录二维码')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: '扫码登录' }));
  await waitFor(() => expect(getFirstPhonePairing()?.phase).toBe('ready'));
  expect(auth.mock.calls.some((call) => call[0].action === 'startQr')).toBe(false);
});
it('keeps beta desktop-login QR separate from any local-owner bootstrap', async () => {
  const auth = vi.fn().mockResolvedValue(signedOut);
  render(<VibeLinkLoginPage bridge={bridge(auth)} />);
  await waitFor(() => expect(auth).toHaveBeenCalledTimes(1));
  await act(async () =>
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'beta' } }),
  );
  fireEvent.click(screen.getByRole('button', { name: '生成登录二维码' }));
  await waitFor(() => expect(auth).toHaveBeenCalledWith({ action: 'startQr', edition: 'beta' }));
  expect(auth).not.toHaveBeenCalledWith({ action: 'useLocalWorkspace' });
  expect(getFirstPhonePairing()).toBeNull();
});
