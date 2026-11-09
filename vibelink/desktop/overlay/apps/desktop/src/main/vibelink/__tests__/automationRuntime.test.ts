import { beforeEach, expect, it, vi } from 'vitest';
const f = vi.hoisted(() => ({
  fetch: vi.fn(),
  active: {
    scope: 'beta_01',
    epoch: 's1/1',
    origin: 'https://hub.vibelink.cloud',
    token: 'fixture-client-only',
  } as { scope: string; epoch: string; origin: string; token: string } | null,
  auth: [] as (() => void)[],
  invalid: [] as (() => void)[],
}));
vi.mock('electron', () => ({ net: { fetch: f.fetch } }));
vi.mock('../workRuntime.js', () => ({ currentHubWorkCredential: () => f.active }));
vi.mock('../authRuntime.js', () => ({
  currentHubCredential: () => ({ session: { expiresAt: Date.now() + 60000 } }),
  isVibeLinkMode: () => true,
  onHubAuthChanged: (cb: () => void) => f.auth.push(cb),
}));
vi.mock('../../appSessionState.js', () => ({
  onAppSessionInvalidated: (cb: () => void) => f.invalid.push(cb),
}));
import { invokeHubAutomation } from '../automationRuntime';
const view = {
 observer: { coverage: 'latest_work_snapshot', baselineAt: 0, lastScanAt: 0, error: '', decisionLimit: 50000, ruleLimit: 256, eventLimit: 10000 },
  ok: true,
  groupId: 'g',
  policy: { mode: 'observe', revision: 0 },
  rules: [],
  decisions: [],
};
beforeEach(() => {
  f.fetch.mockReset();
  f.active = {
    scope: 'beta_01',
    epoch: 's1/1',
    origin: 'https://hub.vibelink.cloud',
    token: 'fixture-client-only',
  };
});
it('sends ordinary beta-client policy reads only to the fixed Hub without cookies or redirects', async () => {
  f.fetch.mockResolvedValue(new Response(JSON.stringify(view)));
  expect(await invokeHubAutomation({ action: 'snapshot', groupId: 'g' })).toMatchObject({
    success: true,
  });
  expect(f.fetch).toHaveBeenCalledWith(
    'https://hub.vibelink.cloud/api/bot-groups/g/automation',
    expect.objectContaining({
      method: 'GET',
      redirect: 'error',
      credentials: 'omit',
      headers: { Authorization: 'Bearer fixture-client-only' },
    }),
  );
});
it('denies a changed destination and unsupported auto mode before network', async () => {
  f.active = { ...f.active!, origin: 'https://example.test' };
  expect(await invokeHubAutomation({ action: 'snapshot', groupId: 'g' })).toMatchObject({
    success: false,
  });
  expect(
    await invokeHubAutomation({
      action: 'setMode',
      groupId: 'g',
      mode: 'auto',
      expectedRevision: 0,
    }),
  ).toMatchObject({ success: false, error: { code: 'INVALID_INPUT' } });
  expect(f.fetch).not.toHaveBeenCalled();
});
it('aborts a pending write at account invalidation and marks its result unconfirmed', async () => {
  let started!: () => void;
  const ready = new Promise<void>((r) => {
    started = r;
  });
  f.fetch.mockImplementation(
    (_url: string, options: RequestInit) =>
      new Promise((_r, reject) => {
        options.signal!.addEventListener('abort', () => reject(Error('aborted')), { once: true });
        started();
      }),
  );
  const pending = invokeHubAutomation({
    action: 'setMode',
    groupId: 'g',
    mode: 'off',
    expectedRevision: 0,
  });
  await ready;
  f.active = null;
  f.invalid.forEach((cb) => cb());
  expect(await pending).toMatchObject({ success: false, error: { code: 'OUTCOME_UNKNOWN' } });
  expect(f.fetch).toHaveBeenCalledTimes(1);
});
