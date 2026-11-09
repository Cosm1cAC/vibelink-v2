// @vitest-environment jsdom
import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { HubAdminPanel } from './HubAdminPanel';
import type { HubSession } from '../../../shared/vibelinkAuth';
import type { AdminResult } from '../../../shared/vibelinkAdmin';
const limits = {
  dailyRequests: 300,
  dailyTokens: 1500000,
  dailyInputBytes: 12000000,
  concurrentRequests: 2,
  requestsPerMinute: 30,
};
const snapshot: Extract<AdminResult, { success: true }>['data'] = {
  kind: 'snapshot',
  day: '2026-11-09',
  seats: [
    {
      tenantId: 'beta_01',
      label: 'Seat One',
      inviteCode: 'fixture-invite-one',
      activatedAt: 0,
      lastDevice: 'Fixture phone',
      limits,
      limitsRevision: 0,
    },
  ],
  users: [
    {
      userId: 'beta_01',
      label: 'Seat One',
      day: '2026-11-09',
      model: 'auto/deepseek-v4.1-flash',
      usedRequests: 2,
      successfulRequests: 1,
      failedRequests: 1,
      interruptedRequests: 0,
      inputTokens: 100,
      outputTokens: 20,
      cachedTokens: 80,
      totalTokens: 120,
      estimatedTokens: 7,
      usageMissingRequests: 1,
      reservedTokens: 9,
      chargedTokens: 136,
      inputBytes: 400,
      limits,
      limitsRevision: 0,
      warning: 'near_limit',
    },
  ],
};
vi.mock('react-i18next', () => {
  const t = (key: string) => key;
  return { useTranslation: () => ({ t }) };
});
it('removes sensitive data automatically when the current session expires', async () => {
  vi.useFakeTimers();
  const admin = vi.fn(async (input: { action: string }): Promise<AdminResult> =>
    input.action === 'capability'
      ? { success: true, data: { kind: 'capability', allowed: true, reason: '' } }
      : input.action === 'snapshot'
        ? { success: true, data: snapshot }
        : { success: true, data: { kind: 'requests', requests: [] } },
  );
  render(<HubAdminPanel session={{ ...owner, expiresAt: Date.now() + 1000 }} bridge={{ admin }} />);
  await act(async () => {
    await vi.advanceTimersByTimeAsync(1);
  });
  expect(screen.getByText('Fixture phone')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: 'vibelinkAdmin.show' }));
  await act(async () => {
    await vi.advanceTimersByTimeAsync(1000);
  });
  expect(screen.queryByText('fixture-invite-one')).toBeNull();
  expect(screen.queryByText('Fixture phone')).toBeNull();
});
it.each(['CONFLICT', 'OUTCOME_UNKNOWN'] as const)(
  'requires an explicit refresh after %s instead of retrying or changing the captured revision',
  async (code) => {
    const admin = vi.fn(async (input: { action: string }): Promise<AdminResult> =>
      input.action === 'capability'
        ? { success: true, data: { kind: 'capability', allowed: true, reason: '' } }
        : input.action === 'snapshot'
          ? { success: true, data: snapshot }
          : input.action === 'setLimits'
            ? { success: false, error: { code, message: 'redacted', recoverable: true } }
            : { success: true, data: { kind: 'requests', requests: [] } },
    );
    render(<HubAdminPanel session={owner} bridge={{ admin }} />);
    fireEvent.click(await screen.findByRole('button', { name: 'vibelinkAdmin.edit beta_01' }));
    fireEvent.click(screen.getByRole('button', { name: 'vibelinkAdmin.save' }));
    await screen.findByText(
      code === 'CONFLICT' ? 'vibelinkAdmin.conflict' : 'vibelinkAdmin.unknown',
    );
    expect(screen.queryByLabelText('vibelinkAdmin.dailyTokens')).toBeNull();
    expect(admin.mock.calls.filter((call) => call[0].action === 'setLimits')).toHaveLength(1);
    expect(
      (screen.getByRole('button', { name: 'vibelinkAdmin.edit beta_01' }) as HTMLButtonElement)
        .disabled,
    ).toBe(true);
  },
);
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});
const owner: HubSession = {
  userId: 'owner',
  label: 'Owner',
  tenantId: null,
  edition: 'dev',
  sessionId: 'admin-a',
  expiresAt: Date.now() + 600000,
  issuer: 'hub_aaaaaaaaaaaaaaaaaaaaaaaa',
  hubId: 'hub_aaaaaaaaaaaaaaaaaaaaaaaa',
  permissions: ['client'],
};
it('does not request admin data for beta or ordinary users and requires an allowed Main capability', async () => {
  const admin = vi.fn(async (): Promise<AdminResult> => ({
    success: true,
    data: { kind: 'capability', allowed: false, reason: 'local owner required' },
  }));
  const view = render(<HubAdminPanel session={{ ...owner, edition: 'beta' }} bridge={{ admin }} />);
  expect(admin).not.toHaveBeenCalled();
  view.rerender(<HubAdminPanel session={{ ...owner, userId: 'client_a' }} bridge={{ admin }} />);
  expect(admin).not.toHaveBeenCalled();
  view.rerender(<HubAdminPanel session={owner} bridge={{ admin }} />);
  await waitFor(() => expect(admin).toHaveBeenCalledExactlyOnceWith({ action: 'capability' }));
  expect(screen.getByText('vibelinkAdmin.denied')).toBeTruthy();
});
it('ignores a late old-owner snapshot after switching to a beta session', async () => {
  let finish!: (value: AdminResult) => void;
  const pending = new Promise<AdminResult>((resolve) => {
    finish = resolve;
  });
  const admin = vi.fn(async (input: { action: string }): Promise<AdminResult> =>
    input.action === 'capability'
      ? { success: true, data: { kind: 'capability', allowed: true, reason: '' } }
      : pending,
  );
  const bridge = { admin };
  const view = render(<HubAdminPanel session={owner} bridge={bridge} />);
  await waitFor(() =>
    expect(admin.mock.calls.some((call) => call[0].action === 'snapshot')).toBe(true),
  );
  view.rerender(
    <HubAdminPanel
      session={{ ...owner, edition: 'beta', sessionId: 'beta-session' }}
      bridge={bridge}
    />,
  );
  await act(async () => finish({ success: true, data: snapshot }));
  expect(screen.queryByText('Fixture phone')).toBeNull();
  expect(screen.queryByText('fixture-invite-one')).toBeNull();
  expect(admin.mock.calls.some((call) => call[0].action === 'requests')).toBe(false);
});
it('submits only an explicitly saved valid limit form with its original revision', async () => {
  const admin = vi.fn(async (input: { action: string }): Promise<AdminResult> =>
    input.action === 'capability'
      ? { success: true, data: { kind: 'capability', allowed: true, reason: '' } }
      : input.action === 'snapshot'
        ? { success: true, data: snapshot }
        : input.action === 'setLimits'
          ? {
              success: true,
              data: {
                kind: 'limitsSaved',
                userId: 'beta_01',
                limits: { ...limits, dailyTokens: 42 },
                limitsRevision: 1,
              },
            }
          : { success: true, data: { kind: 'requests', requests: [] } },
  );
  render(<HubAdminPanel session={owner} bridge={{ admin }} />);
  fireEvent.click(await screen.findByRole('button', { name: 'vibelinkAdmin.edit beta_01' }));
  expect(admin.mock.calls.some((call) => call[0].action === 'setLimits')).toBe(false);
  fireEvent.change(screen.getByLabelText('vibelinkAdmin.dailyTokens'), { target: { value: '42' } });
  fireEvent.click(screen.getByRole('button', { name: 'vibelinkAdmin.save' }));
  await waitFor(() =>
    expect(admin).toHaveBeenCalledWith({
      action: 'setLimits',
      userId: 'beta_01',
      expectedRevision: 0,
      limits: { ...limits, dailyTokens: 42 },
    }),
  );
  await screen.findByText('vibelinkAdmin.saved');
  expect(admin.mock.calls.filter((call) => call[0].action === 'setLimits')).toHaveLength(1);
});
it('loads only after Main allows access and keeps invites masked and measured/cache/estimated usage distinct', async () => {
  const admin = vi.fn(async (input: { action: string }): Promise<AdminResult> =>
    input.action === 'capability'
      ? { success: true, data: { kind: 'capability', allowed: true, reason: '' } }
      : input.action === 'snapshot'
        ? { success: true, data: snapshot }
        : { success: true, data: { kind: 'requests', requests: [] } },
  );
  render(<HubAdminPanel session={owner} bridge={{ admin }} />);
  await screen.findByText('Fixture phone');
  expect(screen.queryByText('fixture-invite-one')).toBeNull();
  expect(screen.getByText('vibelinkAdmin.cacheNote')).toBeTruthy();
  expect(screen.getByText('120')).toBeTruthy();
  expect(screen.getByText('80')).toBeTruthy();
  expect(screen.getByText('7')).toBeTruthy();
  expect(screen.getByText('9')).toBeTruthy();
});
it.each(['AUTH_REQUIRED', 'FORBIDDEN'] as const)(
  'clears invites and editors after %s without an automatic retry',
  async (code) => {
    let reject = false;
    const admin = vi.fn(async (input: { action: string }): Promise<AdminResult> =>
      input.action === 'capability'
        ? { success: true, data: { kind: 'capability', allowed: true, reason: '' } }
        : reject
          ? {
              success: false,
              error: { code, message: 'private server message', recoverable: true },
            }
          : input.action === 'snapshot'
            ? { success: true, data: snapshot }
            : { success: true, data: { kind: 'requests', requests: [] } },
    );
    render(<HubAdminPanel session={owner} bridge={{ admin }} />);
    fireEvent.click(await screen.findByRole('button', { name: 'vibelinkAdmin.show' }));
    expect(screen.getByText('fixture-invite-one')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'vibelinkAdmin.edit beta_01' }));
    reject = true;
    fireEvent.click(screen.getByRole('button', { name: 'vibelinkAdmin.save' }));
    await screen.findByText('vibelinkAdmin.denied');
    expect(screen.queryByText('fixture-invite-one')).toBeNull();
    expect(screen.queryByLabelText('vibelinkAdmin.dailyTokens')).toBeNull();
    expect(screen.queryByText('private server message')).toBeNull();
    expect(admin.mock.calls.filter((call) => call[0].action === 'setLimits')).toHaveLength(1);
  },
);
