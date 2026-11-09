import { expect, it, vi } from 'vitest';
import { HubAutomationService, type AutomationPorts } from '../automationService';
it('denies unsigned callers before reading or changing any policy', async () => {
  const request = vi.fn();
  const s = new HubAutomationService({ credential: () => null, request });
  expect(await s.execute({ action: 'snapshot', groupId: 'g' })).toMatchObject({
    success: false,
    error: { code: 'AUTH_REQUIRED' },
  });
  expect(request).not.toHaveBeenCalled();
});

const identity = {
  scope: 'owner',
  epoch: 's1/1',
  origin: 'http://127.0.0.1:18995',
  token: 'fixture-client',
};
const view = {
 observer: { coverage: 'latest_work_snapshot', baselineAt: 0, lastScanAt: 0, error: '', decisionLimit: 50000, ruleLimit: 256, eventLimit: 10000 }, groupId: 'g', policy: { mode: 'observe', revision: 0 }, rules: [], decisions: [] };
function fixture() {
  const ports = {
    credential: () => identity,
    request: vi.fn<AutomationPorts['request']>(async () => ({
      status: 200,
      data: { ok: true, ...view, adminToken: 'never-forward' },
    })),
  };
  return { ports, service: new HubAutomationService(ports) };
}
it('uses only the authenticated client and strips unexpected server fields', async () => {
  const { ports, service } = fixture();
  expect(await service.execute({ action: 'snapshot', groupId: 'g' })).toEqual({
    success: true,
    data: view,
  });
  expect(ports.request).toHaveBeenCalledExactlyOnceWith(
    identity,
    '/api/bot-groups/g/automation',
    undefined,
  );
});

it('forwards only explicit versioned policy commands using the normal user credential', async () => {
  const { ports, service } = fixture();
  const next = { ...view, policy: { mode: 'off', revision: 1 } };
  ports.request.mockResolvedValue({ status: 200, data: { ok: true, ...next } });
  expect(
    await service.execute({ action: 'setMode', groupId: 'g', mode: 'off', expectedRevision: 0 }),
  ).toEqual({ success: true, data: next });
  expect(ports.request).toHaveBeenCalledExactlyOnceWith(identity, '/api/bot-groups/g/automation', {
    action: 'setMode',
    mode: 'off',
    expectedRevision: 0,
  });
});

it('rejects auto, caller identities, endpoints and unversioned edits before transport', async () => {
  const { ports, service } = fixture();
  for (const raw of [
    { action: 'setMode', groupId: 'g', mode: 'auto', expectedRevision: 0 },
    { action: 'snapshot', groupId: 'g', userId: 'other' },
    { action: 'snapshot', groupId: '../other' },
    { action: 'setMode', groupId: 'g', mode: 'off' },
    {
      action: 'saveRule',
      groupId: 'g',
      ruleId: 'r',
      label: 'a',
      statuses: ['completed', 'completed'],
      expectedRevision: 0,
    },
  ]) {
    expect(await service.execute(raw as never)).toMatchObject({
      success: false,
      error: { code: 'INVALID_INPUT' },
    });
  }
  expect(ports.request).not.toHaveBeenCalled();
});
it('discards a delayed read from a replaced account', async () => {
  const { ports, service } = fixture();
  let active = identity;
  ports.credential = () => active;
  ports.request.mockImplementation(async () => {
    active = { ...identity, epoch: 's2/2' };
    return { status: 200, data: { ok: true, ...view } };
  });
  expect(await service.execute({ action: 'snapshot', groupId: 'g' })).toMatchObject({
    success: false,
    error: { code: 'ACCOUNT_CHANGED' },
  });
});
it('never retries a write whose receipt or account epoch is uncertain', async () => {
  for (const scenario of ['drop', 'epoch', 'bad-receipt', 'server-error']) {
    const { ports, service } = fixture();
    let active = identity;
    ports.credential = () => active;
    ports.request.mockImplementation(async () => {
      if (scenario === 'drop') throw Error('secret backend trace');
      if (scenario === 'epoch') active = { ...identity, epoch: 's2/2' };
      return { status: scenario === 'server-error' ? 500 : 200, data: { ok: true, ...view } };
    });
    const result = await service.execute({
      action: 'setMode',
      groupId: 'g',
      mode: 'off',
      expectedRevision: 0,
    });
    expect(result).toMatchObject({ success: false, error: { code: 'OUTCOME_UNKNOWN' } });
    expect(JSON.stringify(result)).not.toContain('secret');
    expect(ports.request).toHaveBeenCalledTimes(1);
  }
});
it('reports explicit conflicts and denials without exposing server bodies', async () => {
  for (const [status, code] of [
    [409, 'CONFLICT'],
    [401, 'FORBIDDEN'],
    [403, 'FORBIDDEN'],
  ] as const) {
    const { ports, service } = fixture();
    ports.request.mockResolvedValue({ status, data: { error: 'secret raw body' } });
    const result = await service.execute({
      action: 'revokeRule',
      groupId: 'g',
      ruleId: 'r',
      expectedRevision: 0,
    });
    expect(result).toMatchObject({ success: false, error: { code } });
    expect(JSON.stringify(result)).not.toContain('secret');
  }
});
it('does not accept a snapshot belonging to another group', async () => {
  const { ports, service } = fixture();
  ports.request.mockResolvedValue({ status: 200, data: { ok: true, ...view, groupId: 'other' } });
  expect(await service.execute({ action: 'snapshot', groupId: 'g' })).toMatchObject({
    success: false,
    error: { code: 'UNAVAILABLE' },
  });
});
it('keeps a rule save and revoke within their one fixed group endpoint', async () => {
  const { ports, service } = fixture();
  ports.request.mockResolvedValue({
    status: 200,
    data: { ok: true, ...view, policy: { mode: 'observe', revision: 1 } },
  });
  for (const command of [
    { action: 'saveRule', ruleId: 'r', label: 'Rule', statuses: ['failed'], expectedRevision: 0 },
    { action: 'revokeRule', ruleId: 'r', expectedRevision: 0 },
  ]) {
    expect(await service.execute({ ...command, groupId: 'g' } as never)).toMatchObject({
      success: true,
    });
    expect(ports.request).toHaveBeenLastCalledWith(
      identity,
      '/api/bot-groups/g/automation',
      command,
    );
  }
});
