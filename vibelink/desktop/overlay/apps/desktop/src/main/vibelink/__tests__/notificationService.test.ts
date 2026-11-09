import { expect, it } from 'vitest';
import { HubNotificationService, emptyNotificationDisk } from '../notificationService';

it('uses distinct OS IDs for identical Hub work revisions belonging to different accounts', async () => {
  const ids: string[] = [];
  for (const account of ['A', 'B']) {
    let disk = {
      ...emptyNotificationDisk(),
      settings: { enabled: true, preview: false },
      baselined: true,
    };
    const service = new HubNotificationService({
      credential: () => ({
        scope: account,
        epoch: account,
        origin: 'http://127.0.0.1:18995',
        token: 'private',
      }),
      load: () => structuredClone(disk),
      save: (_scope, value) => {
        disk = structuredClone(value);
      },
      scan: async () => [
        {
          id: 'same',
          groupId: 'g',
          updatedAt: 2,
          status: 'completed',
          canManage: true,
          title: 'Private',
        },
      ],
      active: () => false,
      now: () => 100,
      adapter: {
        create: (content) => {
          ids.push(content.id);
          return { show: () => {}, close: () => {} };
        },
      },
      navigate: () => {},
    });
    await service.poll();
  }
  expect(ids[0]).not.toBe(ids[1]);
  expect(ids.every((id) => id.length === 64)).toBe(true);
});

it('fences a completed scan when the current session expires', async () => {
  let now = 100;
  let finish!: (rows: any[]) => void;
  let writes = 0;
  const service = new HubNotificationService({
    credential: () =>
      now < 200
        ? { scope: 'A', epoch: 'a1', origin: 'http://127.0.0.1:18995', token: 'private' }
        : null,
    load: () => ({
      ...emptyNotificationDisk(),
      settings: { enabled: true, preview: false },
      baselined: true,
    }),
    save: () => {
      writes++;
    },
    scan: () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
    active: () => false,
    now: () => now,
    adapter: {
      create: () => {
        throw Error('must not construct');
      },
    },
    navigate: () => {},
  });
  const round = service.poll();
  await Promise.resolve();
  now = 300;
  finish([
    { id: 'w', groupId: 'g', updatedAt: 2, status: 'completed', canManage: true, title: 'Private' },
  ]);
  await round;
  expect(writes).toBe(0);
});

it('separates construction failure from uncertain show and does not automatically retry either', async () => {
  let disk = {
    ...emptyNotificationDisk(),
    settings: { enabled: true, preview: false },
    baselined: true,
  };
  let mode = 'create';
  let shows = 0;
  const service = new HubNotificationService({
    credential: () => ({
      scope: 'A',
      epoch: 'a1',
      origin: 'http://127.0.0.1:18995',
      token: 'private',
    }),
    load: () => structuredClone(disk),
    save: (_scope, value) => {
      disk = structuredClone(value);
    },
    scan: async () => [
      { id: 'w', groupId: 'g', updatedAt: 2, status: 'failed', canManage: true, title: 'Private' },
    ],
    active: () => false,
    now: () => 100,
    adapter: {
      create: () => {
        if (mode === 'create') throw Error('unsupported');
        return {
          show: () => {
            shows++;
            throw Error('pipe lost');
          },
          close: () => {},
        };
      },
    },
    navigate: () => {},
  });
  await service.poll();
  expect(disk.entries[0].state).toBe('failed');
  mode = 'show';
  await service.poll();
  expect(shows).toBe(0);
  await service.execute({ action: 'resend', id: disk.entries[0].id });
  expect(disk.entries[0].state).toBe('unknown');
  await service.poll();
  expect(shows).toBe(1);
});

it('honors an explicit reminder resend even while its settings window is focused', async () => {
  let disk = {
    ...emptyNotificationDisk(),
    settings: { enabled: true, preview: false },
    baselined: true,
  };
  let shown = 0;
  const service = new HubNotificationService({
    credential: () => ({
      scope: 'A',
      epoch: 'a1',
      origin: 'http://127.0.0.1:18995',
      token: 'private',
    }),
    load: () => structuredClone(disk),
    save: (_scope, value) => {
      disk = structuredClone(value);
    },
    scan: async () => [
      {
        id: 'w1',
        groupId: 'g1',
        updatedAt: 2,
        status: 'completed',
        canManage: true,
        title: 'Private',
      },
    ],
    active: () => true,
    now: () => 100,
    adapter: {
      create: () => ({
        show: () => {
          shown++;
        },
        close: () => {},
      }),
    },
    navigate: () => {},
  });
  await service.poll();
  expect(shown).toBe(0);
  await service.execute({ action: 'resend', id: disk.entries[0].id });
  expect(shown).toBe(1);
});

it('keeps notifications off in memory when an encrypted disable write fails', async () => {
  let disk = {
    ...emptyNotificationDisk(),
    settings: { enabled: true, preview: false },
    baselined: true,
  };
  let scans = 0;
  const service = new HubNotificationService({
    credential: () => ({
      scope: 'A',
      epoch: 'a1',
      origin: 'http://127.0.0.1:18995',
      token: 'private',
    }),
    load: () => structuredClone(disk),
    save: (_scope, value) => {
      if (!value.settings.enabled) throw new Error('secure store locked');
      disk = structuredClone(value);
    },
    scan: async () => {
      scans++;
      return [];
    },
    active: () => false,
    now: () => 100,
    adapter: { create: () => ({ show: () => {}, close: () => {} }) },
    navigate: () => {},
  });
  await service.execute({ action: 'configure', enabled: false, preview: false });
  await service.poll();
  expect(scans).toBe(0);
  const snapshot = await service.execute({ action: 'snapshot' });
  expect(snapshot.success && snapshot.data.settings.enabled).toBe(false);
});

it('starts disabled and baselines existing results on first explicit enable', async () => {
  let disk = emptyNotificationDisk();
  let scans = 0;
  let shows = 0;
  const service = new HubNotificationService({
    credential: () => ({
      scope: 'A',
      epoch: 'a1',
      origin: 'http://127.0.0.1:18995',
      token: 'private',
    }),
    load: () => structuredClone(disk),
    save: (_scope: string, value: any) => {
      disk = structuredClone(value);
    },
    scan: async () => {
      scans++;
      return [
        {
          id: 'w1',
          groupId: 'g1',
          updatedAt: 1,
          status: 'completed',
          canManage: true,
          result: 'private',
          title: 'Private',
        },
      ];
    },
    active: () => false,
    now: () => 100,
    adapter: {
      create: () => ({
        show: () => {
          shows++;
        },
        close: () => {},
      }),
    },
    navigate: () => {},
  });
  await service.poll();
  expect(scans).toBe(0);
  await service.execute({ action: 'configure', enabled: true, preview: false });
  await service.poll();
  expect(scans).toBe(1);
  expect(shows).toBe(0);
  expect(disk.baselined).toBe(true);
  expect(disk.entries).toEqual([]);
});

it('recovers a lost OS show as unknown on restart and resends only by explicit reminder action', async () => {
  let disk = {
    ...emptyNotificationDisk(),
    settings: { enabled: true, preview: false },
    baselined: true,
  };
  let shows = 0;
  const ports = {
    credential: () => ({
      scope: 'A',
      epoch: 'a1',
      origin: 'http://127.0.0.1:18995',
      token: 'private',
    }),
    load: () => structuredClone(disk),
    save: (_scope: string, value: any) => {
      disk = structuredClone(value);
    },
    scan: async () => [
      {
        id: 'w1',
        groupId: 'g1',
        updatedAt: 2,
        status: 'failed',
        canManage: true,
        title: 'Private',
      },
    ],
    active: () => false,
    now: () => 100,
    adapter: {
      create: () => ({
        show: () => {
          shows++;
        },
        close: () => {},
      }),
    },
    navigate: () => {},
  };
  await new HubNotificationService(ports).poll();
  expect(shows).toBe(1);
  const restarted = new HubNotificationService(ports);
  await restarted.poll();
  expect(shows).toBe(1);
  expect(disk.entries[0].state).toBe('unknown');
  await restarted.execute({ action: 'resend', id: disk.entries[0].id });
  expect(shows).toBe(2);
  expect(disk.entries[0].attempt).toBe(2);
});

it('does not create an OS popup if secure persistence fails', async () => {
  let created = 0;
  const service = new HubNotificationService({
    credential: () => ({
      scope: 'A',
      epoch: 'a1',
      origin: 'http://127.0.0.1:18995',
      token: 'private',
    }),
    load: () => ({
      ...emptyNotificationDisk(),
      settings: { enabled: true, preview: false },
      baselined: true,
    }),
    save: () => {
      throw new Error('DPAPI unavailable');
    },
    scan: async () => [
      {
        id: 'w1',
        groupId: 'g1',
        updatedAt: 2,
        status: 'completed',
        canManage: true,
        title: 'Private',
      },
    ],
    active: () => false,
    now: () => 100,
    adapter: {
      create: () => {
        created++;
        return { show: () => {}, close: () => {} };
      },
    },
    navigate: () => {},
  });
  await service.poll();
  expect(created).toBe(0);
  const configured = await service.execute({ action: 'configure', enabled: true, preview: false });
  expect(configured.success ? '' : configured.error.code).toBe('STORAGE_UNAVAILABLE');
});

it('suppresses foreground updates and never notifies other owners or ordinary progress', async () => {
  let disk = {
    ...emptyNotificationDisk(),
    settings: { enabled: true, preview: false },
    baselined: true,
  };
  let shown = 0;
  const service = new HubNotificationService({
    credential: () => ({
      scope: 'A',
      epoch: 'a1',
      origin: 'http://127.0.0.1:18995',
      token: 'private',
    }),
    load: () => structuredClone(disk),
    save: (_scope: string, value: any) => {
      disk = structuredClone(value);
    },
    scan: async () => [
      {
        id: 'foreign',
        groupId: 'g1',
        updatedAt: 2,
        status: 'completed',
        canManage: false,
        title: 'Other',
      },
      {
        id: 'running',
        groupId: 'g1',
        updatedAt: 2,
        status: 'running',
        canManage: true,
        title: 'Progress',
      },
      {
        id: 'own',
        groupId: 'g1',
        updatedAt: 2,
        status: 'needs_input',
        canManage: true,
        title: 'Own',
      },
    ],
    active: () => true,
    now: () => 100,
    adapter: {
      create: () => ({
        show: () => {
          shown++;
        },
        close: () => {},
      }),
    },
    navigate: () => {},
  });
  await service.poll();
  expect(shown).toBe(0);
  expect(disk.entries.map((e) => e.workId)).toEqual(['own']);
  expect(disk.entries[0].state).toBe('suppressed');
});

it('disable aborts a scan immediately and re-enable baselines without replaying old updates', async () => {
  let disk = {
    ...emptyNotificationDisk(),
    settings: { enabled: true, preview: false },
    baselined: true,
  };
  let signal!: AbortSignal;
  let shown = 0;
  let scanCount = 0;
  const service = new HubNotificationService({
    credential: () => ({
      scope: 'A',
      epoch: 'a1',
      origin: 'http://127.0.0.1:18995',
      token: 'private',
    }),
    load: () => structuredClone(disk),
    save: (_scope: string, value: any) => {
      disk = structuredClone(value);
    },
    scan: async (_credential, currentSignal) => {
      scanCount++;
      signal = currentSignal;
      if (scanCount === 1)
        return new Promise((_resolve, reject) =>
          currentSignal.addEventListener('abort', () => reject(new Error('abort'))),
        );
      return [
        {
          id: 'old',
          groupId: 'g1',
          updatedAt: 4,
          status: 'completed',
          canManage: true,
          title: 'Old',
        },
      ];
    },
    active: () => false,
    now: () => 100,
    adapter: {
      create: () => ({
        show: () => {
          shown++;
        },
        close: () => {},
      }),
    },
    navigate: () => {},
  });
  const round = service.poll();
  await Promise.resolve();
  const off = service.execute({ action: 'configure', enabled: false, preview: false });
  expect(signal.aborted).toBe(true);
  await round;
  await off;
  await service.execute({ action: 'configure', enabled: true, preview: false });
  await service.poll();
  expect(shown).toBe(0);
  expect(disk.entries).toEqual([]);
});

it('records OS show/click/close independently and only opens a matching current account', async () => {
  let disk = {
    ...emptyNotificationDisk(),
    settings: { enabled: true, preview: false },
    baselined: true,
  };
  let account = 'A';
  let handlers: any;
  let navigated = 0;
  const service = new HubNotificationService({
    credential: () => ({
      scope: account,
      epoch: account,
      origin: 'http://127.0.0.1:18995',
      token: 'private',
    }),
    load: () => structuredClone(disk),
    save: (_scope: string, value: any) => {
      disk = structuredClone(value);
    },
    scan: async () => [
      {
        id: 'w1',
        groupId: 'g1',
        updatedAt: 2,
        status: 'needs_input',
        canManage: true,
        title: 'Private',
      },
    ],
    active: () => false,
    now: () => 100,
    adapter: {
      create: (_content: any, callbacks: any) => {
        handlers = callbacks;
        return { show: () => {}, close: () => {} };
      },
    },
    navigate: () => {
      navigated++;
    },
  });
  await service.poll();
  handlers.show();
  handlers.click();
  handlers.close();
  await service.execute({ action: 'snapshot' });
  expect(disk.entries[0].state).toBe('delivered');
  expect(disk.entries[0].receipts.map((x) => x.type)).toEqual(['show', 'click', 'close']);
  expect(navigated).toBe(1);
  account = 'B';
  handlers.click();
  await service.execute({ action: 'snapshot' });
  expect(navigated).toBe(1);
});

it('discards a late scan after account switch before writing either account journal', async () => {
  let account = 'A';
  let finish!: (rows: any[]) => void;
  const disk = {
    ...emptyNotificationDisk(),
    settings: { enabled: true, preview: false },
    baselined: true,
  };
  let writes = 0,
    shows = 0;
  const service = new HubNotificationService({
    credential: () => ({
      scope: account,
      epoch: account,
      origin: 'http://127.0.0.1:18995',
      token: 'private',
    }),
    load: () => structuredClone(disk),
    save: () => {
      writes++;
    },
    scan: () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
    active: () => false,
    now: () => 100,
    adapter: {
      create: () => ({
        show: () => {
          shows++;
        },
        close: () => {},
      }),
    },
    navigate: () => {},
  });
  const pending = service.poll();
  await Promise.resolve();
  account = 'B';
  finish([
    {
      id: 'w1',
      groupId: 'g1',
      updatedAt: 2,
      status: 'completed',
      canManage: true,
      title: 'A private',
    },
  ]);
  await pending;
  expect(writes).toBe(0);
  expect(shows).toBe(0);
});

it('persists dispatching before show, hides previews and deduplicates one Hub revision', async () => {
  let disk = {
    ...emptyNotificationDisk(),
    settings: { enabled: true, preview: false },
    baselined: true,
  };
  let shows = 0;
  const service = new HubNotificationService({
    credential: () => ({
      scope: 'A',
      epoch: 'a1',
      origin: 'http://127.0.0.1:18995',
      token: 'private',
    }),
    load: () => structuredClone(disk),
    save: (_scope: string, value: any) => {
      disk = structuredClone(value);
    },
    scan: async () => [
      {
        id: 'w1',
        groupId: 'g1',
        updatedAt: 2,
        status: 'completed',
        canManage: true,
        result: 'PRIVATE RESULT',
        title: 'PRIVATE TITLE',
      },
    ],
    active: () => false,
    now: () => 100,
    adapter: {
      create: (content: any) => {
        expect(JSON.stringify(content)).not.toContain('PRIVATE');
        return {
          show: () => {
            expect(disk.entries[0].state).toBe('dispatching');
            shows++;
          },
          close: () => {},
        };
      },
    },
    navigate: () => {},
  });
  await service.poll();
  await service.poll();
  expect(shows).toBe(1);
  expect(disk.entries).toHaveLength(1);
});
