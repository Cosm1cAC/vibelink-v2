import { expect, it, vi } from 'vitest';
import { HubAdminService, type AdminPorts } from '../adminService';

it('does not read a privileged credential or contact the beta Hub without a verified local owner', async () => {
  const ports = { identity: () => null, readAdmin: vi.fn(), request: vi.fn() };
  const service = new HubAdminService(ports);
  expect(await service.execute({ action: 'snapshot', day: '2026-11-09' })).toMatchObject({ success: false, error: { code: 'AUTH_REQUIRED' } });
  expect(ports.readAdmin).not.toHaveBeenCalled();
  expect(ports.request).not.toHaveBeenCalled();
});

it('reports local-owner capability without reading or returning the beta administrator token', async () => {
  const ports = { identity: () => ({ scope: 'local-v1', epoch: 'session-1/1' }), readAdmin: vi.fn(), request: vi.fn() };
  expect(await new HubAdminService(ports).execute({ action: 'capability' })).toMatchObject({ success: true, data: { kind: 'capability', allowed: true } });
  expect(ports.readAdmin).not.toHaveBeenCalled();
  expect(ports.request).not.toHaveBeenCalled();
});

const limits = { dailyRequests: 300, dailyTokens: 1500000, dailyInputBytes: 12000000, concurrentRequests: 2, requestsPerMinute: 30 };
const usage = { userId: 'beta_01', label: 'Seat 1', day: '2026-11-09', model: 'auto/deepseek-v4.1-flash', usedRequests: 2, successfulRequests: 2, failedRequests: 0, interruptedRequests: 0, inputTokens: 10, outputTokens: 2, cachedTokens: 7, totalTokens: 12, estimatedTokens: 0, usageMissingRequests: 0, reservedTokens: 0, chargedTokens: 12, inputBytes: 50, limits, limitsRevision: 0, warning: '' };
const seat = { tenantId: 'beta_01', label: 'Seat 1', inviteCode: 'fixture-invite', activatedAt: 0, lastDevice: '', limits, limitsRevision: 0 };
const identity = { scope: 'local-v1', epoch: 'session-1/1' };
function fixture() {
  const ports = { identity: () => identity, readAdmin: vi.fn(async () => 'fixture-beta-admin-secret'), request: vi.fn<AdminPorts['request']>(async (_identity: unknown, _token: string, endpoint: string) => ({ status: 200, data: endpoint === '/api/beta/tenants' ? { betaMode: true, tenants: [{ ...seat, clientToken: 'never-expose-client', agentToken: 'never-expose-agent' }] } : { day: usage.day, users: [{ ...usage, Authorization: 'never-expose-header' }] } })) };
  return { ports, service: new HubAdminService(ports) };
}
it('returns only administrator DTO fields and keeps cached tokens inside input totals', async () => {
  const { service, ports } = fixture();
  const result = await service.execute({ action: 'snapshot', day: usage.day });
  expect(result).toEqual({ success: true, data: { kind: 'snapshot', day: usage.day, seats: [seat], users: [usage] } });
  expect(JSON.stringify(result)).not.toMatch(/never-expose|fixture-beta-admin-secret/);
  expect(ports.request.mock.calls.map((call) => call[2])).toEqual(['/api/beta/tenants', '/api/usage/users?day=2026-11-09']);
});

it('returns a denied error on administrator 401 or 403 without exposing raw error bodies', async () => {
  const { service, ports } = fixture();
  ports.request.mockResolvedValue({ status: 403, data: { error: 'fixture-beta-admin-secret' } });
  const result = await service.execute({ action: 'snapshot', day: usage.day });
  expect(result).toMatchObject({ success: false, error: { code: 'FORBIDDEN' } });
  expect(JSON.stringify(result)).not.toContain('fixture-beta-admin-secret');
});

it('sanitizes recent request records while preserving missing-usage estimates', async () => {
  const { service, ports } = fixture();
  const request = { requestId: 'req-1', userId: 'beta_01', workId: '', day: usage.day, model: usage.model, protocol: 'responses', startedAt: 1, finishedAt: 2, durationMs: 1, outcome: 'interrupted', httpStatus: null, tokens: null, usageMissing: true, estimatedTokens: 120 };
  ports.request.mockImplementation(async (_id, _token, endpoint) => ({ status: 200, data: endpoint === '/api/beta/tenants' ? { betaMode: true, tenants: [seat] } : { requests: [{ ...request, prompt: 'never-expose-prompt', Authorization: 'never-expose-header' }] } }));
  const result = await service.execute({ action: 'requests' });
  expect(result).toEqual({ success: true, data: { kind: 'requests', requests: [request] } });
  expect(JSON.stringify(result)).not.toMatch(/never-expose/);
});

it('saves a known seat with its exact expected revision and reports the new revision', async () => {
  const { service, ports } = fixture();
  ports.request.mockImplementation(async (_id, _token, endpoint) => ({ status: 200, data: endpoint === '/api/beta/tenants' ? { betaMode: true, tenants: [seat] } : { ok: true, limitsRevision: 1 } }));
  const result = await service.execute({ action: 'setLimits', userId: 'beta_01', limits, expectedRevision: 0 });
  expect(result).toEqual({ success: true, data: { kind: 'limitsSaved', userId: 'beta_01', limits, limitsRevision: 1 } });
  expect(ports.request).toHaveBeenLastCalledWith(identity, 'fixture-beta-admin-secret', '/api/usage/limits', { userId: 'beta_01', limits, expectedRevision: 0 });
});

it('reports an unconfirmed write after a dropped reply without claiming that no update happened', async () => {
 const { service, ports } = fixture();
 ports.request.mockImplementation(async (_id, _token, endpoint) => {
  if (endpoint === '/api/beta/tenants') return { status: 200, data: { betaMode: true, tenants: [seat] } };
  throw new Error('network dropped after the server accepted a private body');
 });
 expect(await service.execute({ action: 'setLimits', userId: 'beta_01', limits, expectedRevision: 0 })).toMatchObject({ success: false, error: { code: 'OUTCOME_UNKNOWN' } });
 expect(ports.request.mock.calls.filter((call) => call[2] === '/api/usage/limits')).toHaveLength(1);
});

it('refuses a stale editor or a non-seat target without posting any limit update', async () => {
 const { service, ports } = fixture();
 expect(await service.execute({ action: 'setLimits', userId: 'beta_01', limits, expectedRevision: 9 })).toMatchObject({ success: false, error: { code: 'CONFLICT' } });
 expect(await service.execute({ action: 'setLimits', userId: 'owner', limits, expectedRevision: 0 })).toMatchObject({ success: false, error: { code: 'FORBIDDEN' } });
 expect(ports.request.mock.calls.every((call) => call[2] === '/api/beta/tenants')).toBe(true);
});

it('drops a delayed snapshot from an expired or replaced administrator session', async () => {
 const { service, ports } = fixture();
 let current: typeof identity | null = identity;
 ports.identity = () => current!;
 let release!: (value: { status: number; data: unknown }) => void;
 ports.request.mockImplementation(() => new Promise((resolve) => { release = resolve; }));
 const pending = service.execute({ action: 'snapshot', day: usage.day });
 await vi.waitFor(() => expect(release).toBeTypeOf('function'));
 current = { ...identity, epoch: 'session-2/2' };
 release({ status: 200, data: { betaMode: true, tenants: [seat] } });
 const result = await pending;
 expect(result).toMatchObject({ success: false, error: { code: 'ACCOUNT_CHANGED' } });
 expect(JSON.stringify(result)).not.toContain('fixture-invite');
 expect(ports.request).toHaveBeenCalledTimes(1);
});

it('rejects caller-controlled destinations and invalid limits before touching credentials', async () => {
 const { service, ports } = fixture();
 for (const input of [{ action: 'snapshot', day: '2026-02-30' }, { action: 'snapshot', day: usage.day, origin: 'https://attacker.invalid' }, { action: 'requests', token: 'injected' }, { action: 'setLimits', userId: 'beta_01', expectedRevision: 0, limits: { ...limits, concurrentRequests: 0 } }]) {
  expect(await service.execute(input as never)).toMatchObject({ success: false, error: { code: 'INVALID_INPUT' } });
 }
 expect(ports.readAdmin).not.toHaveBeenCalled(); expect(ports.request).not.toHaveBeenCalled();
});

it('reads pre-Work audit rows without inventing a task association', async () => {
 const { service, ports } = fixture();
 const oldRequest = { requestId: 'legacy-request', userId: 'beta_01', day: usage.day, model: usage.model, protocol: 'responses', startedAt: 1, finishedAt: 2, durationMs: 1, outcome: 'success', httpStatus: 200, tokens: { input: 10, output: 2, cached: 7, total: 12 }, usageMissing: false, estimatedTokens: 0 };
 ports.request.mockImplementation(async (_id, _token, endpoint) => ({ status: 200, data: endpoint === '/api/beta/tenants' ? { betaMode: true, tenants: [seat] } : { requests: [oldRequest] } }));
 expect(await service.execute({ action: 'requests' })).toEqual({ success: true, data: { kind: 'requests', requests: [{ ...oldRequest, workId: '' }] } });
});
