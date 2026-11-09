// @vitest-environment jsdom
import React from 'react';
import { act, cleanup, render, screen, fireEvent, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { NotificationPanel } from './NotificationPanel';
import type { NotificationInput } from '../../../shared/vibelinkNotifications';
vi.mock('react-i18next', () => {
  const t = (key: string) => key;
  return { useTranslation: () => ({ t }) };
});
afterEach(cleanup);
const snapshot = {
  scope: 'A',
  settings: { enabled: false, preview: false },
  baselined: false,
  entries: [],
  error: '',
};
it('defaults to disabled and only sends explicit configuration after a user choice', async () => {
  const notifications = vi.fn(async () => ({ success: true as const, data: snapshot }));
  render(<NotificationPanel accountKey="A" bridge={{ notifications }} />);
  const toggle = await screen.findByLabelText('vibelinkNotifications.enabled');
  expect((toggle as HTMLInputElement).checked).toBe(false);
  expect(notifications.mock.calls).toHaveLength(1);
  fireEvent.click(toggle);
  await waitFor(() =>
    expect(notifications).toHaveBeenCalledWith({
      action: 'configure',
      enabled: true,
      preview: false,
    }),
  );
});
it('never applies late account A settings to account B', async () => {
  let finish!: (value: any) => void;
  const a = {
    notifications: vi.fn(
      () =>
        new Promise<any>((resolve) => {
          finish = resolve;
        }),
    ),
  };
  const b = {
    notifications: vi.fn(async () => ({
      success: true as const,
      data: { ...snapshot, scope: 'B' },
    })),
  };
  const view = render(<NotificationPanel accountKey="A" bridge={a} />);
  view.rerender(<NotificationPanel accountKey="B" bridge={b} />);
  await waitFor(() => expect(b.notifications).toHaveBeenCalled());
  await act(async () => {
    finish({
      success: true,
      data: { ...snapshot, scope: 'A', settings: { enabled: true, preview: true } },
    });
  });
  expect((screen.getByLabelText('vibelinkNotifications.enabled') as HTMLInputElement).checked).toBe(
    false,
  );
  expect((screen.getByLabelText('vibelinkNotifications.preview') as HTMLInputElement).checked).toBe(
    false,
  );
});
it('requires an explicit resend for unknown delivery and never calls a Work action', async () => {
  const entry = {
    id: 'notice-1',
    key: 'g/w/2/completed',
    groupId: 'g',
    workId: 'w',
    eventStatus: 'completed' as const,
    updatedAt: 2,
    createdAt: 1,
    state: 'unknown' as const,
    attempt: 1,
    attemptedAt: 0,
    error: '',
    receipts: [],
    preview: null,
  };
  const notifications = vi.fn(async (_input: NotificationInput) => ({
    success: true as const,
    data: { ...snapshot, settings: { enabled: true, preview: false }, entries: [entry] },
  }));
  render(<NotificationPanel accountKey="A" bridge={{ notifications }} />);
  const button = await screen.findByRole('button', { name: 'vibelinkNotifications.resend' });
  expect(notifications).not.toHaveBeenCalledWith(expect.objectContaining({ action: 'resend' }));
  fireEvent.click(button);
  await waitFor(() =>
    expect(notifications).toHaveBeenCalledWith({ action: 'resend', id: 'notice-1' }),
  );
  expect(
    notifications.mock.calls.every((args) =>
      ['snapshot', 'resend'].includes((args[0] as any)?.action),
    ),
  ).toBe(true);
});
it('does not restore old settings when a poll returns after an explicit disable', async () => {
  vi.useFakeTimers();
  try {
    let finish!: (value: any) => void;
    let count = 0;
    const notifications = vi.fn(async (input: NotificationInput) => {
      if (input.action === 'configure') return { success: true as const, data: snapshot };
      if (++count === 1)
        return {
          success: true as const,
          data: { ...snapshot, settings: { enabled: true, preview: false } },
        };
      return new Promise<any>((resolve) => {
        finish = resolve;
      });
    });
    render(<NotificationPanel accountKey="A" bridge={{ notifications }} />);
    await act(async () => {
      await Promise.resolve();
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000);
    });
    fireEvent.click(screen.getByLabelText('vibelinkNotifications.enabled'));
    await act(async () => {
      await Promise.resolve();
    });
    await act(async () => {
      finish({ success: true, data: { ...snapshot, settings: { enabled: true, preview: false } } });
    });
    expect(
      (screen.getByLabelText('vibelinkNotifications.enabled') as HTMLInputElement).checked,
    ).toBe(false);
  } finally {
    vi.useRealTimers();
  }
});
