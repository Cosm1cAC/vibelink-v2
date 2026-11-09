// @vitest-environment jsdom
import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { HubWorkPanel } from './HubWorkPanel';
import type { HubSession } from '../../../shared/vibelinkAuth';
import type { HubWorkResult } from '../../../shared/vibelinkWork';
vi.mock('react-i18next', () => {
  const t = (key: string) => key;
  return { useTranslation: () => ({ t }) };
});
afterEach(cleanup);
const session: HubSession = {
  userId: 'a',
  label: 'A',
  tenantId: null,
  edition: 'dev',
  sessionId: 'a',
  expiresAt: Date.now() + 100000,
  issuer: 'hub_aaaaaaaaaaaaaaaaaaaaaaaa',
  hubId: 'hub_aaaaaaaaaaaaaaaaaaaaaaaa',
  permissions: ['client'],
};
const row = (id: string) => ({
  id,
  groupId: 'g',
  title: id,
  text: '',
  status: 'completed',
  phase: '',
  createdAt: 1,
  updatedAt: 1,
  error: '',
  result: '',
  canManage: false,
  retryable: false,
  archived: false,
  acceptance: '',
  inputs: [],
  steps: [],
});
it('selects a notification target only from the authorized Hub group and work projection', async () => {
  const work = vi.fn(async (input: any) => {
    if (input.action === 'groups')
      return {
        success: true,
        data: { kind: 'groups', groups: [{ id: 'g', name: 'G', botIds: [] }] },
      };
    if (input.action === 'list')
      return {
        success: true,
        data: { kind: 'list', work: [{ ...row('target'), result: 'TARGET_DETAIL' }] },
      };
    return { success: true, data: { kind: 'outbox', outbox: [] } };
  });
  render(
    <HubWorkPanel
      session={session}
      bridge={{ work: work as never }}
      targetGroupId="g"
      targetWorkId="target"
    />,
  );
  await screen.findByText('TARGET_DETAIL');
  expect(work.mock.calls.some(([input]) => ['submit', 'manage'].includes(input.action))).toBe(
    false,
  );
});
it('ignores a slow response for an earlier search after the newer search finishes', async () => {
  let late: (result: HubWorkResult) => void = () => {};
  const work = vi.fn(async (input) => {
    if (input.action === 'groups')
      return {
        success: true,
        data: { kind: 'groups', groups: [{ id: 'g', name: 'G', botIds: [] }] },
      };
    if (input.action === 'list' && !input.q)
      return new Promise<HubWorkResult>((resolve) => {
        late = resolve;
      });
    if (input.action === 'list')
      return { success: true, data: { kind: 'list', work: [row('new')] } };
    return { success: true, data: { kind: 'outbox', outbox: [] } };
  });
  render(<HubWorkPanel session={session} bridge={{ work: work as never }} />);
  await waitFor(() => expect(work.mock.calls.some(([i]) => i.action === 'list')).toBe(true));
  fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'new' } });
  await screen.findByText('new');
  await act(async () => {
    late({ success: true, data: { kind: 'list', work: [row('old')] } });
  });
  expect(screen.queryByText('old')).toBeNull();
  expect(screen.getByText('new')).toBeTruthy();
});
it('clears work when accounts switch and discards late results from the previous session', async () => {
  let resolveA: (result: HubWorkResult) => void = () => {};
  let count = 0;
  const work = vi.fn(async (input) => {
    if (input.action === 'groups') {
      count++;
      return count === 1
        ? new Promise<HubWorkResult>((r) => {
            resolveA = r;
          })
        : { success: true, data: { kind: 'groups', groups: [] } };
    }
    return { success: true, data: { kind: 'outbox', outbox: [] } };
  });
  const { rerender } = render(<HubWorkPanel session={session} bridge={{ work: work as never }} />);
  await waitFor(() => expect(count).toBe(1));
  rerender(
    <HubWorkPanel
      session={{ ...session, userId: 'b', sessionId: 'b' }}
      bridge={{ work: work as never }}
    />,
  );
  await waitFor(() => expect(count).toBe(2));
  await act(async () => {
    resolveA({
      success: true,
      data: {
        kind: 'groups',
        groups: [{ id: 'old', name: 'PRIVATE A', description: '', botIds: [] }],
      },
    });
  });
  expect(screen.queryByText('PRIVATE A')).toBeNull();
});
it('releases the submit lock when a search changes while delivery is pending', async () => {
  let release: (result: HubWorkResult) => void = () => {};
  const work = vi.fn(async (input) => {
    if (input.action === 'groups')
      return {
        success: true,
        data: { kind: 'groups', groups: [{ id: 'g', name: 'G', botIds: [] }] },
      };
    if (input.action === 'submit')
      return new Promise<HubWorkResult>((r) => {
        release = r;
      });
    return {
      success: true,
      data: { kind: input.action === 'list' ? 'list' : 'outbox', work: [], outbox: [] },
    };
  });
  render(<HubWorkPanel session={session} bridge={{ work: work as never }} />);
  await screen.findByRole('searchbox');
  fireEvent.change(screen.getByRole('textbox'), { target: { value: 'task' } });
  fireEvent.click(screen.getByRole('button', { name: 'vibelinkWork.send' }));
  fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'other' } });
  await act(async () => {
    release({ success: true, data: { kind: 'accepted', requestId: 'r' } });
  });
  expect((screen.getByRole('textbox') as HTMLTextAreaElement).value).toBe('');
  fireEvent.change(screen.getByRole('textbox'), { target: { value: 'next' } });
  expect(
    (screen.getByRole('button', { name: 'vibelinkWork.send' }) as HTMLButtonElement).disabled,
  ).toBe(false);
});
it('does not queue more polls while a slow Hub list request is still running', async () => {
  vi.useFakeTimers();
  try {
    const work = vi.fn(async (input) =>
      input.action === 'groups'
        ? { success: true, data: { kind: 'groups', groups: [{ id: 'g', name: 'G', botIds: [] }] } }
        : new Promise(() => {}),
    );
    render(<HubWorkPanel session={session} bridge={{ work: work as never }} />);
    await act(async () => {});
    expect(work.mock.calls.filter(([input]) => input.action === 'list')).toHaveLength(1);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(20000);
    });
    expect(work.mock.calls.filter(([input]) => input.action === 'list')).toHaveLength(1);
  } finally {
    cleanup();
    vi.useRealTimers();
  }
});
it('offers explicit dismissal only for failed local records and never for pending delivery', async () => {
  const work = vi.fn(async (input) => {
    if (input.action === 'groups')
      return {
        success: true,
        data: { kind: 'groups', groups: [{ id: 'g', name: 'G', botIds: [] }] },
      };
    if (input.action === 'list') return { success: true, data: { kind: 'list', work: [] } };
    if (input.action === 'dismissFailed')
      return { success: true, data: { kind: 'outbox', outbox: [] } };
    return {
      success: true,
      data: {
        kind: 'outbox',
        outbox: [
          {
            requestId: 'failed',
            groupId: 'g',
            text: 'failedtask',
            status: 'failed',
            createdAt: 1,
            error: 'rejected',
          },
          {
            requestId: 'pending',
            groupId: 'g',
            text: 'pendingtask',
            status: 'pending',
            createdAt: 1,
          },
        ],
      },
    };
  });
  render(<HubWorkPanel session={session} bridge={{ work: work as never }} />);
  await screen.findByText(/failedtask/);
  const dismiss = screen.getAllByText('vibelinkWork.dismissFailed');
  expect(dismiss).toHaveLength(1);
  fireEvent.click(dismiss[0]);
  await waitFor(() =>
    expect(work).toHaveBeenCalledWith({ action: 'dismissFailed', requestId: 'failed' }),
  );
  await waitFor(() => expect(screen.queryByText(/failedtask/)).toBeNull());
});
it('does not clear another group draft or release its pending send when an old group reply arrives', async () => {
  const releases: Array<(result: HubWorkResult) => void> = [];
  const work = vi.fn(async (input) => {
    if (input.action === 'groups')
      return {
        success: true,
        data: {
          kind: 'groups',
          groups: [
            { id: 'a', name: 'A', botIds: [] },
            { id: 'b', name: 'B', botIds: [] },
          ],
        },
      };
    if (input.action === 'submit')
      return new Promise<HubWorkResult>((resolve) => releases.push(resolve));
    return {
      success: true,
      data: { kind: input.action === 'list' ? 'list' : 'outbox', work: [], outbox: [] },
    };
  });
  render(<HubWorkPanel session={session} bridge={{ work: work as never }} />);
  await screen.findByRole('combobox');
  fireEvent.change(screen.getByRole('textbox'), { target: { value: 'same text' } });
  fireEvent.click(screen.getByRole('button', { name: 'vibelinkWork.send' }));
  fireEvent.change(screen.getByRole('combobox'), { target: { value: 'b' } });
  fireEvent.change(screen.getByRole('textbox'), { target: { value: 'same text' } });
  fireEvent.click(screen.getByRole('button', { name: 'vibelinkWork.send' }));
  expect(releases).toHaveLength(2);
  await act(async () => {
    releases[0]({ success: true, data: { kind: 'accepted', requestId: 'a' } });
  });
  expect((screen.getByRole('textbox') as HTMLTextAreaElement).value).toBe('same text');
  expect(
    (screen.getByRole('button', { name: 'vibelinkWork.send' }) as HTMLButtonElement).disabled,
  ).toBe(true);
});
