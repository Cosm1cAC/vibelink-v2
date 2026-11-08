import { expect, it } from 'vitest';
import { HubWorkService } from '../workService';

it('retains its delivery ID when a successful HTTP response lacks a matching work receipt', async () => {
  let saved: any = { entries: [], read: {} };
  const service = new HubWorkService({
    credential: () => ({ scope: 'A', epoch: 's1', origin: 'http://127.0.0.1:18995', token: 'private' }),
    load: () => structuredClone(saved), save: (_scope, value) => { saved = structuredClone(value); }, requestId: () => 'receipt-unknown',
    request: async () => ({ status: 202, data: {} }),
  });
  await service.execute({ action: 'submit', groupId: 'g1', text: 'One task' });
  expect(saved.entries[0]?.requestId).toBe('receipt-unknown');
  expect(saved.entries[0]?.status).toBe('pending');
});

it('rejects caller-provided endpoints, identities and request IDs before any IO', async () => {
  let touched = false;
  const service = new HubWorkService({
    credential: () => { touched = true; return null; },
    load: () => { throw new Error('must not read'); }, save: () => { throw new Error('must not write'); }, requestId: () => 'unused',
    request: async () => { throw new Error('must not send'); },
  });
  const result = await service.execute({ action: 'submit', groupId: 'g1', text: 'Prompt', requestId: 'attacker', endpoint: 'https://other.invalid', token: 'untrusted' } as any);
  expect(result.success ? '' : result.error.code).toBe('INVALID_INPUT');
  expect(touched).toBe(false);
});

it('keeps 503 and 429 pending across service restart and reuses exactly the saved ID', async () => {
  let saved: any = { entries: [], read: {} }; const sent: string[] = []; const statuses = [503, 429, 202];
  const ports: any = {
    credential: () => ({ scope: 'A', epoch: 's1', origin: 'http://127.0.0.1:18995', token: 'private' }),
    load: () => structuredClone(saved), save: (_scope: string, value: any) => { saved = structuredClone(value); }, requestId: () => 'immutable-id',
    request: async (_credential: unknown, _path: string, body: any) => { sent.push(body.requestId); return { status: statuses.shift(), data: {work:{id:'w1',groupId:'g1'}} }; },
  };
  await new HubWorkService(ports).execute({ action: 'submit', groupId: 'g1', text: 'One task' });
  expect(saved.entries[0].status).toBe('pending');
  await new HubWorkService(ports).execute({ action: 'outbox' });
  expect(saved.entries[0].status).toBe('pending');
  await new HubWorkService(ports).execute({ action: 'outbox' });
  expect(saved.entries).toEqual([]); expect(sent).toEqual(['immutable-id', 'immutable-id', 'immutable-id']);
});

it('does not implicitly replay pending tasks when cancelling another work', async () => {
  const disk: any = { entries: [{ requestId: 'pending-id', groupId: 'g1', text: 'Do not dispatch', status: 'pending', createdAt: 1 }], read: {} }; const paths: string[] = [];
  const service = new HubWorkService({
    credential: () => ({ scope: 'A', epoch: 's1', origin: 'http://127.0.0.1:18995', token: 'private' }),
    load: () => structuredClone(disk), save: () => {}, requestId: () => 'unused',
    request: async (_credential, path) => { paths.push(path); return { status: 200, data: { work: [{ id: 'w1', canManage: true }] } }; },
  });
  expect((await service.execute({ action: 'manage', groupId: 'g1', workId: 'w1', command: 'cancel' })).success).toBe(true);
  expect(paths).toEqual(['/api/bot-groups/g1/work?archived=true', '/api/bot-groups/g1/work/w1/actions']);
});

it('persists the immutable request before dispatch and retries its ID after a lost response', async () => {
  let saved: any = null;
  const sent: any[] = [];
  const service = new HubWorkService({
    credential: () => ({
      scope: 'A',
      epoch: 's1',
      origin: 'http://127.0.0.1:18995',
      token: 'private',
    }),
    load: () => saved ?? { entries: [], read: {} },
    save: (_scope: string, value: any) => {
      saved = structuredClone(value);
    },
    requestId: () => 'request-1',
    request: async (_credential: unknown, _path: string, body: any) => {
      expect(saved.entries[0].requestId).toBe('request-1');
      sent.push(body);
      if (sent.length === 1) throw new Error('response lost');
      return { status: 202, data: { ok: true, work: { id: 'w1', groupId: 'g1' } } };
    },
  });
  const first = await service.execute({ action: 'submit', groupId: 'g1', text: 'Hello' });
  // Durable acceptance clears the composer even while transport is offline.
  expect(first.success && first.data.requestId).toBe('request-1');
  const second = await service.execute({ action: 'outbox' });
  expect(second.success).toBe(true);
  expect(sent).toHaveLength(2);
  expect(sent[0]).toEqual(sent[1]);
  expect(saved.entries).toEqual([]);
});

it('preserves the original pending ID when a lost acceptance is followed by 401', async () => {
  let saved: any = { entries: [], read: {} };
  let calls = 0;
  const ports: any = {
    credential: () => ({
      scope: 'A',
      epoch: 's1',
      origin: 'http://127.0.0.1:18995',
      token: 'private',
    }),
    load: () => structuredClone(saved),
    save: (_scope: string, value: any) => {
      saved = structuredClone(value);
    },
    requestId: () => 'lost-accepted-id',
    request: async () => {
      calls++;
      if (calls === 1) throw new Error('Hub accepted but response lost');
      return { status: 401, data: {} };
    },
  };
  await new HubWorkService(ports).execute({
    action: 'submit',
    groupId: 'g1',
    text: 'Already may be running',
  });
  const result = await new HubWorkService(ports).execute({ action: 'outbox' });
  expect(result.success ? '' : result.error.code).toBe('AUTH_REQUIRED');
  expect(saved.entries[0].status).toBe('pending');
  expect(saved.entries[0].requestId).toBe('lost-accepted-id');
  const removed = await new HubWorkService(ports).execute({
    action: 'dismissFailed',
    requestId: 'lost-accepted-id',
  });
  expect(removed.success).toBe(false);
  expect(saved.entries).toHaveLength(1);
});

it('serializes journal updates so a late acceptance cannot erase the next request', async () => {
  let saved: any = { entries: [], read: {} };
  let count = 0;
  let release!: () => void;
  const firstResponse = new Promise<void>((resolve) => {
    release = resolve;
  });
  const sent: string[] = [];
  const service = new HubWorkService({
    credential: () => ({
      scope: 'A',
      epoch: 's1',
      origin: 'http://127.0.0.1:18995',
      token: 'private',
    }),
    load: () => structuredClone(saved),
    save: (_scope, value) => {
      saved = structuredClone(value);
    },
    requestId: () => `r${++count}`,
    request: async (_credential, _endpoint, body: any) => {
      sent.push(body.requestId);
      if (sent.length === 1) await firstResponse;
      else throw new Error('offline');
      return { status: 202, data: {work:{id:'w1',groupId:'g1'}} };
    },
  });
  const first = service.execute({ action: 'submit', groupId: 'g1', text: 'First' });
  await Promise.resolve();
  const second = service.execute({ action: 'submit', groupId: 'g1', text: 'Second' });
  release();
  await first;
  await second;
  expect(saved.entries.map((entry: any) => entry.requestId)).toEqual(['r2']);
});

it('reads groups through the current credential and strips unrelated server fields', async () => {
  const service = new HubWorkService({
    credential: () => ({
      scope: 'A',
      epoch: 's1',
      origin: 'http://127.0.0.1:18995',
      token: 'private',
    }),
    load: () => ({ entries: [], read: {} }),
    save: () => {},
    requestId: () => 'unused',
    request: async (credential, endpoint) => {
      expect(credential.token).toBe('private');
      expect(endpoint).toBe('/api/bots');
      return {
        status: 200,
        data: { groups: [{ id: 'g1', name: 'Team', token: 'must-not-cross' }] },
      };
    },
  });
  const result = await service.execute({ action: 'groups' });
  expect(result.success && result.data.groups?.[0]).toEqual({
    id: 'g1',
    name: 'Team',
    description: '',
    botIds: [],
  });
});

it('lists authoritative work and deduplicates read status by work revision', async () => {
  let saved: any = { entries: [], read: {} };
  const service = new HubWorkService({
    credential: () => ({
      scope: 'A',
      epoch: 's1',
      origin: 'http://127.0.0.1:18995',
      token: 'private',
    }),
    load: () => structuredClone(saved),
    save: (_scope, value) => {
      saved = structuredClone(value);
    },
    requestId: () => 'unused',
    request: async () => ({
      status: 200,
      data: {
        work: [
          {
            id: 'w1',
            groupId: 'g1',
            title: 'Task',
            status: 'completed',
            createdAt: 1,
            updatedAt: 2,
            result: 'Done',
            canManage: true,
          },
        ],
      },
    }),
  });
  const first = await service.execute({ action: 'list', groupId: 'g1' });
  expect(first.success && first.data.work?.[0].unread).toBe(true);
  await service.execute({ action: 'markRead', groupId: 'g1', workId: 'w1', updatedAt: 2 });
  const second = await service.execute({ action: 'list', groupId: 'g1' });
  expect(second.success && second.data.work?.[0].unread).toBe(false);
});

it('does not send anything when durable persistence fails', async () => {
  let sent = 0;
  const service = new HubWorkService({
    credential: () => ({
      scope: 'A',
      epoch: 's1',
      origin: 'http://127.0.0.1:18995',
      token: 'private',
    }),
    load: () => ({ entries: [], read: {} }),
    save: () => {
      throw new Error('disk full');
    },
    requestId: () => 'request-3',
    request: async () => {
      sent++;
      return { status: 202, data: {} };
    },
  });
  const result = await service.execute({ action: 'submit', groupId: 'g1', text: 'Do not lose me' });
  expect(result.success ? '' : result.error.code).toBe('STORAGE_UNAVAILABLE');
  expect(sent).toBe(0);
});

it('rejects management when the server omits canManage and never sends an action', async () => {
  const paths: string[] = [];
  const service = new HubWorkService({
    credential: () => ({
      scope: 'A',
      epoch: 's1',
      origin: 'http://127.0.0.1:18995',
      token: 'private',
    }),
    load: () => ({ entries: [], read: {} }),
    save: () => {},
    requestId: () => 'unused',
    request: async (_credential, endpoint) => {
      paths.push(endpoint);
      return { status: 200, data: { work: [{ id: 'w1', groupId: 'g1' }] } };
    },
  });
  const result = await service.execute({
    action: 'manage',
    groupId: 'g1',
    workId: 'w1',
    command: 'retry',
  });
  expect(result.success ? '' : result.error.code).toBe('FORBIDDEN');
  expect(paths).toEqual(['/api/bot-groups/g1/work?archived=true']);
});

it('fences late account A responses and never dispatches A queue under B', async () => {
  let account = { scope: 'A', epoch: 's1', origin: 'http://127.0.0.1:18995', token: 'private-A' };
  const disks = new Map<string, any>();
  let finish!: (v: any) => void;
  const request = new Promise<any>((resolve) => {
    finish = resolve;
  });
  const service = new HubWorkService({
    credential: () => account,
    load: (scope) => disks.get(scope) ?? { entries: [], read: {} },
    save: (scope, value) => {
      disks.set(scope, structuredClone(value));
    },
    requestId: () => 'request-2',
    request: async () => request,
  });
  const pending = service.execute({ action: 'submit', groupId: 'g1', text: 'A only' });
  await Promise.resolve();
  account = { ...account, scope: 'B', epoch: 's2', token: 'private-B' };
  finish({ status: 202, data: {} });
  const result = await pending;
  expect(result.success ? '' : result.error.code).toBe('ACCOUNT_CHANGED');
  const b = await service.execute({ action: 'outbox' });
  expect(b.success && b.data.outbox).toEqual([]);
});
