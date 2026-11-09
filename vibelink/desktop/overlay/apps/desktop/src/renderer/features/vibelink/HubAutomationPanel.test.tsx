// @vitest-environment jsdom
import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { HubAutomationPanel } from './HubAutomationPanel';
import type { HubSession } from '../../../shared/vibelinkAuth';
import type { AutomationResult, AutomationView } from '../../../shared/vibelinkAutomation';
import type { HubWorkResult } from '../../../shared/vibelinkWork';
vi.mock('react-i18next', () => {
  const t = (key: string) => key;
  return { useTranslation: () => ({ t }) };
});
it('starts a blank rule only on request and saves only after explicit label and statuses', async () => {
  const automation = vi.fn(async (): Promise<AutomationResult> => ({ success: true, data: view }));
  render(
    <HubAutomationPanel
      session={session}
      bridge={{ automation, work: vi.fn(async () => groups) }}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: 'vibelinkAutomation.title' }));
  await waitFor(() =>
    expect(
      (screen.getByRole('button', { name: 'vibelinkAutomation.newRule' }) as HTMLButtonElement)
        .disabled,
    ).toBe(false),
  );
  fireEvent.click(screen.getByRole('button', { name: 'vibelinkAutomation.newRule' }));
  expect((screen.getByLabelText('vibelinkAutomation.ruleLabel') as HTMLInputElement).value).toBe(
    '',
  );
  expect(screen.getAllByRole('checkbox').some((input) => (input as HTMLInputElement).checked)).toBe(
    false,
  );
  expect(automation).toHaveBeenCalledTimes(1);
  fireEvent.change(screen.getByLabelText('vibelinkAutomation.ruleLabel'), {
    target: { value: 'Ask for missing input' },
  });
  fireEvent.click(screen.getByLabelText('vibelinkAutomation.needs_input'));
  fireEvent.click(screen.getByRole('button', { name: 'vibelinkAutomation.saveRule' }));
  await waitFor(() =>
    expect(automation).toHaveBeenCalledWith({
      action: 'saveRule',
      groupId: 'g1',
      ruleId: expect.stringMatching(/^rule_/),
      label: 'Ask for missing input',
      statuses: ['needs_input'],
      expectedRevision: 4,
    }),
  );
});
it('edits a rule as a new version using the policy revision captured when the form opened', async () => {
  const rule = {
    id: 'r1',
    label: 'Review failures',
    statuses: ['failed'] as const,
    version: 2,
    createdAt: 1,
    revokedAt: 0,
  };
  const initial: AutomationView = { ...view, rules: [{ ...rule, statuses: ['failed'] }] };
  const automation = vi.fn(async (input: { action: string }): Promise<AutomationResult> => ({
    success: true,
    data:
      input.action === 'saveRule'
        ? {
            ...initial,
            policy: { mode: 'off', revision: 5 },
            rules: [{ ...rule, statuses: ['failed'], version: 3, label: 'Review failures v3' }],
          }
        : initial,
  }));
  render(
    <HubAutomationPanel
      session={session}
      bridge={{ automation, work: vi.fn(async () => groups) }}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: 'vibelinkAutomation.title' }));
  fireEvent.click(await screen.findByRole('button', { name: 'vibelinkAutomation.editRule r1' }));
  expect((screen.getByLabelText('vibelinkAutomation.ruleLabel') as HTMLInputElement).value).toBe(
    'Review failures',
  );
  fireEvent.change(screen.getByLabelText('vibelinkAutomation.ruleLabel'), {
    target: { value: 'Review failures v3' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'vibelinkAutomation.saveRule' }));
  await waitFor(() =>
    expect(automation).toHaveBeenCalledWith({
      action: 'saveRule',
      groupId: 'g1',
      ruleId: 'r1',
      label: 'Review failures v3',
      statuses: ['failed'],
      expectedRevision: 4,
    }),
  );
  await screen.findByText('Review failures v3');
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});
it('can open a new rule form when the browser has no secure-context randomUUID API', async () => {
  vi.stubGlobal('crypto', { getRandomValues: crypto.getRandomValues.bind(crypto) });
  const automation = vi.fn(async (): Promise<AutomationResult> => ({ success: true, data: view }));
  render(
    <HubAutomationPanel
      session={session}
      bridge={{ automation, work: vi.fn(async () => groups) }}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: 'vibelinkAutomation.title' }));
  await waitFor(() =>
    expect(
      (screen.getByRole('button', { name: 'vibelinkAutomation.newRule' }) as HTMLButtonElement)
        .disabled,
    ).toBe(false),
  );
  fireEvent.click(screen.getByRole('button', { name: 'vibelinkAutomation.newRule' }));
  expect(screen.getByLabelText('vibelinkAutomation.ruleLabel')).toBeTruthy();
});
it('shows paused audit recording without claiming execution stopped or offering auto mode', async () => {
  const automation = vi.fn(async (): Promise<AutomationResult> => ({
    success: true,
    data: {
      ...view,
      policy: { mode: 'observe', revision: 4 },
      observer: { ...view.observer, error: 'capacity' },
    },
  }));
  render(
    <HubAutomationPanel
      session={session}
      bridge={{ automation, work: vi.fn(async () => groups) }}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: 'vibelinkAutomation.title' }));
  await screen.findByText('vibelinkAutomation.capacityPaused');
  expect(screen.getByText('vibelinkAutomation.snapshotNote')).toBeTruthy();
  expect(screen.queryByRole('option', { name: 'auto' })).toBeNull();
  expect(automation).toHaveBeenCalledExactlyOnceWith({ action: 'snapshot', groupId: 'g1' });
});
it('keeps prior rule versions read-only and revokes only through the current policy revision', async () => {
  const initial: AutomationView = {
    ...view,
    rules: [
      {
        id: 'r1',
        label: 'Old version',
        statuses: ['failed'],
        version: 1,
        createdAt: 1,
        revokedAt: 2,
      },
      {
        id: 'r1',
        label: 'Current version',
        statuses: ['failed'],
        version: 2,
        createdAt: 2,
        revokedAt: 0,
      },
    ],
    decisions: [
      {
        id: 'd1',
        eventId: 'e1',
        workId: 'w1',
        workRevision: 8,
        status: 'failed',
        ruleId: 'r1',
        ruleVersion: 1,
        at: 3,
        outcome: 'suppressed',
        reason: 'revoked',
      },
    ],
  };
  const automation = vi.fn(async (input: { action: string }): Promise<AutomationResult> => ({
    success: true,
    data:
      input.action === 'revokeRule'
        ? {
            ...initial,
            policy: { mode: 'off', revision: 5 },
            rules: initial.rules.map((rule) => ({ ...rule, revokedAt: 99 })),
          }
        : initial,
  }));
  render(
    <HubAutomationPanel
      session={session}
      bridge={{ automation, work: vi.fn(async () => groups) }}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: 'vibelinkAutomation.title' }));
  await screen.findByText('Current version');
  expect(screen.getAllByRole('button', { name: 'vibelinkAutomation.editRule r1' })).toHaveLength(1);
  expect(screen.getByText('vibelinkAutomation.reason_revoked')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: 'vibelinkAutomation.revoke r1' }));
  await waitFor(() =>
    expect(automation).toHaveBeenCalledWith({
      action: 'revokeRule',
      groupId: 'g1',
      ruleId: 'r1',
      expectedRevision: 4,
    }),
  );
  await waitFor(() =>
    expect(screen.queryByRole('button', { name: 'vibelinkAutomation.editRule r1' })).toBeNull(),
  );
});
it('fences an old account response when a new Hub session replaces it', async () => {
  let finish!: (value: AutomationResult) => void;
  const pending = new Promise<AutomationResult>((resolve) => {
    finish = resolve;
  });
  let reads = 0;
  const automation = vi.fn(async (): Promise<AutomationResult> =>
    ++reads === 1
      ? pending
      : {
          success: true,
          data: {
            ...view,
            rules: [
              {
                id: 'rb',
                label: 'New account rule',
                statuses: ['completed'],
                version: 1,
                createdAt: 1,
                revokedAt: 0,
              },
            ],
          },
        },
  );
  const bridge = { automation, work: vi.fn(async () => groups) };
  const page = render(<HubAutomationPanel session={session} bridge={bridge} />);
  fireEvent.click(screen.getByRole('button', { name: 'vibelinkAutomation.title' }));
  await waitFor(() => expect(reads).toBe(1));
  page.rerender(
    <HubAutomationPanel
      session={{ ...session, sessionId: 'auto-b', userId: 'beta_02', tenantId: 'beta_02' }}
      bridge={bridge}
    />,
  );
  await screen.findByText('New account rule');
  await act(async () =>
    finish({
      success: true,
      data: {
        ...view,
        rules: [
          {
            id: 'ra',
            label: 'Old account private rule',
            statuses: ['failed'],
            version: 1,
            createdAt: 1,
            revokedAt: 0,
          },
        ],
      },
    }),
  );
  expect(screen.queryByText('Old account private rule')).toBeNull();
  expect(screen.getByText('New account rule')).toBeTruthy();
});
it('does not apply a late snapshot from the previous group', async () => {
  let finish!: (value: AutomationResult) => void;
  const pending = new Promise<AutomationResult>((resolve) => {
    finish = resolve;
  });
  const automation = vi.fn(async (input: { groupId: string }): Promise<AutomationResult> =>
    input.groupId === 'g1'
      ? pending
      : {
          success: true,
          data: {
            ...view,
            groupId: 'g2',
            policy: { mode: 'off', revision: 1 },
            rules: [
              {
                id: 'r2',
                label: 'Only group two',
                statuses: ['completed'],
                version: 1,
                createdAt: 1,
                revokedAt: 0,
              },
            ],
          },
        },
  );
  render(
    <HubAutomationPanel
      session={session}
      bridge={{ automation, work: vi.fn(async () => groups) }}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: 'vibelinkAutomation.title' }));
  await waitFor(() =>
    expect(automation).toHaveBeenCalledWith({ action: 'snapshot', groupId: 'g1' }),
  );
  fireEvent.change(screen.getByLabelText('vibelinkAutomation.group'), { target: { value: 'g2' } });
  await screen.findByText('Only group two');
  await act(async () =>
    finish({ success: true, data: { ...view, policy: { mode: 'observe', revision: 99 } } }),
  );
  expect((screen.getByLabelText('vibelinkAutomation.mode') as HTMLSelectElement).value).toBe('off');
  expect(screen.getByText('vibelinkAutomation.policyVersion v1')).toBeTruthy();
});
it.each(['CONFLICT', 'OUTCOME_UNKNOWN'] as const)(
  'locks all writes after %s until a successful explicit refresh',
  async (code) => {
    let snapshots = 0;
    const automation = vi.fn(async (input: { action: string }): Promise<AutomationResult> =>
      input.action === 'snapshot'
        ? {
            success: true,
            data: { ...view, policy: { mode: 'off', revision: ++snapshots === 1 ? 4 : 5 } },
          }
        : { success: false, error: { code, message: 'not shown' } },
    );
    render(
      <HubAutomationPanel
        session={session}
        bridge={{ automation, work: vi.fn(async () => groups) }}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'vibelinkAutomation.title' }));
    await waitFor(() =>
      expect((screen.getByLabelText('vibelinkAutomation.mode') as HTMLSelectElement).value).toBe(
        'off',
      ),
    );
    fireEvent.change(screen.getByLabelText('vibelinkAutomation.mode'), {
      target: { value: 'observe' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'vibelinkAutomation.saveMode' }));
    await screen.findByText(
      code === 'CONFLICT' ? 'vibelinkAutomation.conflict' : 'vibelinkAutomation.unknown',
    );
    expect((screen.getByLabelText('vibelinkAutomation.mode') as HTMLSelectElement).disabled).toBe(
      true,
    );
    expect(
      (screen.getByRole('button', { name: 'vibelinkAutomation.newRule' }) as HTMLButtonElement)
        .disabled,
    ).toBe(true);
    expect(snapshots).toBe(1);
    expect(automation.mock.calls.filter((call) => call[0].action === 'setMode')).toHaveLength(1);
    fireEvent.click(screen.getByRole('button', { name: 'vibelinkAutomation.refresh' }));
    await screen.findByText('vibelinkAutomation.policyVersion v5');
    expect(
      (screen.getByRole('button', { name: 'vibelinkAutomation.newRule' }) as HTMLButtonElement)
        .disabled,
    ).toBe(false);
  },
);
const session: HubSession = {
  userId: 'beta_01',
  label: 'Fixture',
  tenantId: 'beta_01',
  edition: 'beta',
  sessionId: 'auto-a',
  expiresAt: Date.now() + 600000,
  issuer: 'hub_aaaaaaaaaaaaaaaaaaaaaaaa',
  hubId: 'hub_aaaaaaaaaaaaaaaaaaaaaaaa',
  permissions: ['client'],
};
const view: AutomationView = {
  groupId: 'g1',
  policy: { mode: 'off', revision: 4 },
  observer: {
    coverage: 'latest_work_snapshot',
    baselineAt: 0,
    lastScanAt: 0,
    error: '',
    decisionLimit: 50000,
    ruleLimit: 256,
    eventLimit: 10000,
  },
  rules: [],
  decisions: [],
};
const groups: HubWorkResult = {
  success: true,
  data: {
    kind: 'groups',
    groups: [
      { id: 'g1', name: 'Group One', botIds: [], description: '' },
      { id: 'g2', name: 'Group Two', botIds: [], description: '' },
    ],
  },
};
it('does not query while collapsed or infer observe before the selected group snapshot arrives', async () => {
  let finish!: (value: AutomationResult) => void;
  const automation = vi.fn(
    () =>
      new Promise<AutomationResult>((resolve) => {
        finish = resolve;
      }),
  );
  const work = vi.fn(async () => groups);
  render(<HubAutomationPanel session={session} bridge={{ automation, work }} />);
  expect(work).not.toHaveBeenCalled();
  expect(automation).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: 'vibelinkAutomation.title' }));
  await waitFor(() =>
    expect(automation).toHaveBeenCalledExactlyOnceWith({ action: 'snapshot', groupId: 'g1' }),
  );
  expect((screen.getByLabelText('vibelinkAutomation.mode') as HTMLSelectElement).value).toBe('');
  await act(async () => finish({ success: true, data: view }));
  expect((screen.getByLabelText('vibelinkAutomation.mode') as HTMLSelectElement).value).toBe('off');
  expect(work).toHaveBeenCalledExactlyOnceWith({ action: 'groups' });
});
it('changes mode only after explicit save using the fetched policy revision and never dispatches Work', async () => {
  const automation = vi.fn(async (input: { action: string }): Promise<AutomationResult> => ({
    success: true,
    data: input.action === 'setMode' ? { ...view, policy: { mode: 'observe', revision: 5 } } : view,
  }));
  const work = vi.fn(async () => groups);
  render(<HubAutomationPanel session={session} bridge={{ automation, work }} />);
  fireEvent.click(screen.getByRole('button', { name: 'vibelinkAutomation.title' }));
  await waitFor(() =>
    expect((screen.getByLabelText('vibelinkAutomation.mode') as HTMLSelectElement).value).toBe(
      'off',
    ),
  );
  fireEvent.change(screen.getByLabelText('vibelinkAutomation.mode'), {
    target: { value: 'observe' },
  });
  expect(automation).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByRole('button', { name: 'vibelinkAutomation.saveMode' }));
  await waitFor(() =>
    expect(automation).toHaveBeenCalledWith({
      action: 'setMode',
      groupId: 'g1',
      mode: 'observe',
      expectedRevision: 4,
    }),
  );
  await screen.findByText('vibelinkAutomation.policyVersion v5');
  expect(work.mock.calls).toEqual([[{ action: 'groups' }]]);
});
