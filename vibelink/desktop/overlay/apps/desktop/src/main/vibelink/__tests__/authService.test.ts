import { expect, it, vi } from 'vitest';
// Production execute/pollQr/expiry protect Hub identity, encrypted publication
// order and owner teardown. Original Cindy login tests do not exercise these
// contracts. Smallest regressions are fresh startup, delayed writes/owner
// commits and denied ACK; unit tier uses injected I/O and no real credentials.
import { hubDataOwnerId, HubAuthService, type HubCredential } from '../authService';

const session = { userId: 'beta_01', label: '测试用户', tenantId: 'beta_01', edition: 'beta' as const, sessionId: 's_test', expiresAt: 9999999999999, issuer: `hub_${'a'.repeat(24)}`, hubId: `hub_${'a'.repeat(24)}`, permissions: ['client'] as ['client'] };

it('keeps a fresh profile signed out without reading local admin credentials', async () => {
  const ports = {
    readCredential: vi.fn(async () => null),
    request: vi.fn(),
    readLocalAdmin: vi.fn(),
    commitOwner: vi.fn(),
  };
  const service = new HubAuthService(ports);
  const result = await service.execute({ action: 'initialize' });
  expect(result).toEqual({ success: true, data: { status: 'signed_out', session: null, qr: null } });
  expect(ports.request).not.toHaveBeenCalled();
  expect(ports.readLocalAdmin).not.toHaveBeenCalled();
  expect(ports.commitOwner).not.toHaveBeenCalled();
});

it('revokes the local capability when the Hub explicitly denies an acknowledgment', async () => {
  const clearCredential = vi.fn(async () => undefined);
  const commitOwner = vi.fn(async (_owner: string | null) => undefined);
  const service = new HubAuthService({
    readCredential: async () => null, readLocalAdmin: vi.fn(), ensureVault: async () => undefined,
    writeCredential: vi.fn(async () => undefined), clearCredential, commitOwner,
    request: async (_origin, path) => {
      if (path.endsWith('/start')) return { status: 200, data: { id: 'qr_fake', pollSecret: 'fake-proof', link: 'vibelink://login?s=fake', verificationCode: '12', expiresAt: 9999999999999, purpose: 'desktop_login' } };
      if (path.endsWith('/poll')) return { status: 200, data: { status: 'approved', token: 'fake-revoked-before-ack', session } };
      return { status: 200, data: { status: 'denied' } };
    },
  });
  await service.execute({ action: 'startQr', edition: 'beta' });
  expect(await service.pollQr()).toMatchObject({ success: true, data: { status: 'signed_out', session: null } });
  expect(service.getCredential()).toBeNull();
  expect(clearCredential).toHaveBeenCalledOnce();
  expect(commitOwner).toHaveBeenLastCalledWith(null);
});

it('requires logout before a beta identity can request the local-owner login capability', async () => {
  const readLocalAdmin = vi.fn(async () => 'fake-admin-must-not-read');
  const service = new HubAuthService({
    readCredential: async () => ({ origin: 'https://hub.vibelink.cloud', token: 'fake-beta', session }),
    readLocalAdmin, commitOwner: vi.fn(), ensureVault: async () => undefined,
    request: async () => ({ status: 200, data: { session } }),
  });
  await service.execute({ action: 'initialize' });
  expect(await service.execute({ action: 'useLocalWorkspace' })).toMatchObject({ success: false, error: { code: 'LOGIN_FAILED' } });
  expect(readLocalAdmin).not.toHaveBeenCalled();
  expect(service.getSnapshot().session?.edition).toBe('beta');
});

it('does not resurrect an identity when logout interrupts its owner teardown commit', async () => {
  let releaseOwner!: () => void;
  let enteredOwner!: () => void;
  const entered = new Promise<void>((resolve) => { enteredOwner = resolve; });
  const blocked = new Promise<void>((resolve) => { releaseOwner = resolve; });
  let active: string | null = null;
  let stored: unknown = null;
  const service = new HubAuthService({
    readCredential: async () => null, readLocalAdmin: vi.fn(), ensureVault: async () => undefined,
    writeCredential: async (credential) => { stored = credential; }, clearCredential: async () => { stored = null; },
    request: async () => ({ status: 200, data: { token: 'fake-delayed-owner', session } }),
    commitOwner: async (owner) => { if (owner) { enteredOwner(); await blocked; } active = owner; },
  });
  const login = service.execute({ action: 'activateBeta', code: 'fake' });
  await entered;
  const logout = service.execute({ action: 'logout' });
  releaseOwner();
  const [lateLogin] = await Promise.all([login, logout]);
  expect(lateLogin).toMatchObject({ success: true, data: { status: 'signed_out', session: null } });
  expect(service.getCredential()).toBeNull();
  expect(active).toBeNull();
  expect(stored).toBeNull();
});

it('expires an open session through the same owner boundary without restarting the app', async () => {
  const clock = vi.spyOn(Date, 'now').mockReturnValue(100);
  try {
    const expiring = { ...session, expiresAt: 200 };
    const commitOwner = vi.fn(async (_owner: string | null) => undefined);
    const clearCredential = vi.fn(async () => undefined);
    const revokeCapability = vi.fn();
    const service = new HubAuthService({
      readCredential: async () => ({ origin: 'https://hub.vibelink.cloud', token: 'fake-expiring', session: expiring }),
      request: async () => ({ status: 200, data: { session: expiring } }), readLocalAdmin: vi.fn(), commitOwner, clearCredential, revokeCapability,
    });
    await service.execute({ action: 'initialize' });
    clock.mockReturnValue(300);
    expect(service.getCredential()).toBeNull();
    await service.expireIfNeeded();
    expect(service.getSnapshot()).toMatchObject({ status: 'signed_out', session: null });
    expect(clearCredential).toHaveBeenCalledOnce();
    expect(commitOwner).toHaveBeenLastCalledWith(null);
    expect(revokeCapability).toHaveBeenCalledOnce();
  } finally { clock.mockRestore(); }
});

it('serializes a delayed credential write with logout and a second account login', async () => {
  let releaseWrite!: () => void;
  let enteredWrite!: () => void;
  const entered = new Promise<void>((resolve) => { enteredWrite = resolve; });
  const blocked = new Promise<void>((resolve) => { releaseWrite = resolve; });
  const second = { ...session, userId: 'beta_02', tenantId: 'beta_02', sessionId: 's_second' };
  let stored: HubCredential | null = null;
  const commitOwner = vi.fn(async (_owner: string | null) => undefined);
  const service = new HubAuthService({
    readCredential: async () => null, readLocalAdmin: vi.fn(), ensureVault: async () => undefined, commitOwner,
    clearCredential: async () => { stored = null; },
    writeCredential: async (credential) => {
      if (credential.session.userId === session.userId) { enteredWrite(); await blocked; }
      stored = credential;
    },
    request: async (_origin, _path, input) => ({ status: 200, data: { token: 'fake-client', session: input.body?.code === 'second' ? second : session } }),
  });
  const firstLogin = service.execute({ action: 'activateBeta', code: 'first' });
  await entered;
  const logout = service.execute({ action: 'logout' });
  const secondLogin = service.execute({ action: 'activateBeta', code: 'second' });
  releaseWrite();
  await Promise.all([firstLogin, logout, secondLogin]);
  expect(service.getSnapshot().session?.userId).toBe('beta_02');
  expect(service.getCredential()?.session.userId).toBe('beta_02');
  expect((stored as HubCredential | null)?.session.userId).toBe('beta_02');
  expect(commitOwner).toHaveBeenLastCalledWith(expect.stringMatching(/^hub_/));
  expect(commitOwner).not.toHaveBeenCalledWith(hubDataOwnerId(session));
});

it('retries an acknowledgment lost after persistence without recommitting the account', async () => {
  const writeCredential = vi.fn(async () => undefined);
  const commitOwner = vi.fn(async () => undefined);
  let acknowledgments = 0;
  const service = new HubAuthService({
    readCredential: async () => null, readLocalAdmin: vi.fn(), ensureVault: async () => undefined, writeCredential, commitOwner,
    request: async (_origin, path) => {
      if (path.endsWith('/start')) return { status: 200, data: { id: 'qr_fake', pollSecret: 'fake-proof', link: 'vibelink://login?s=fake', verificationCode: '12', expiresAt: 9999999999999, purpose: 'desktop_login' } };
      if (path.endsWith('/poll')) return { status: 200, data: { status: 'approved', token: 'fake-approved', session } };
      if (++acknowledgments === 1) throw new Error('connection lost');
      return { status: 200, data: { status: 'consumed' } };
    },
  });
  await service.execute({ action: 'startQr', edition: 'beta' });
  await service.pollQr();
  expect(service.getSnapshot().status).toBe('authenticated');
  await service.pollQr();
  expect(writeCredential).toHaveBeenCalledOnce();
  expect(commitOwner).toHaveBeenCalledOnce();
  expect(acknowledgments).toBe(2);
});

it('reports a network outage without misdiagnosing it as unavailable credential storage', async () => {
  const service = new HubAuthService({ readCredential: async () => null, readLocalAdmin: vi.fn(), commitOwner: vi.fn(), ensureVault: async () => undefined, request: async () => { throw new Error('fetch failed fake-private-token'); } });
  const result = await service.execute({ action: 'startQr', edition: 'beta' });
  expect(result).toMatchObject({ success: false, error: { code: 'NETWORK_UNAVAILABLE' } });
  expect(JSON.stringify(result)).not.toContain('fake-private-token');
});

it('does not recreate a cancelled QR when its start request finishes late', async () => {
  let finish!: (value: { status: number; data: unknown }) => void;
  const pending = new Promise<{ status: number; data: unknown }>((resolve) => { finish = resolve; });
  const service = new HubAuthService({ readCredential: async () => null, readLocalAdmin: vi.fn(), commitOwner: vi.fn(), ensureVault: async () => undefined, request: async () => pending });
  const starting = service.execute({ action: 'startQr', edition: 'beta' });
  await Promise.resolve();
  await service.execute({ action: 'cancelQr' });
  finish({ status: 200, data: { id: 'qr_test', pollSecret: 'fake-poll', link: 'vibelink://login?s=fake-scan', verificationCode: '42', expiresAt: 9999999999999, purpose: 'desktop_login' } });
  expect(await starting).toMatchObject({ success: true, data: { qr: null, status: 'signed_out' } });
});

it('does not expose transport errors or retain an unauthorized persisted credential', async () => {
  const clearCredential = vi.fn(async () => undefined);
  const service = new HubAuthService({
    readCredential: async () => ({ origin: 'https://hub.vibelink.cloud', token: 'fake-revoked', session }),
    readLocalAdmin: vi.fn(), commitOwner: vi.fn(), clearCredential,
    request: async () => ({ status: 401, data: { error: 'fake-secret-server-error' } }),
  });
  const result = await service.execute({ action: 'initialize' });
  expect(result).toMatchObject({ success: false, error: { code: 'AUTH_EXPIRED' } });
  expect(clearCredential).toHaveBeenCalledOnce();
  expect(JSON.stringify(result)).not.toContain('fake-secret-server-error');
});

it('logs out only the current Hub session and tears down its owner even when the Hub is offline', async () => {
  const clearCredential = vi.fn(async () => undefined);
  const commitOwner = vi.fn(async (_owner: string | null) => undefined);
  const request = vi.fn(async (_origin: string, path: string) => {
    if (path.endsWith('/me')) return { status: 200, data: { session } };
    throw new Error('offline fake-token-must-not-leak');
  });
  const service = new HubAuthService({
    readCredential: async () => ({ origin: 'https://hub.vibelink.cloud', token: 'fake-current', session }),
    readLocalAdmin: vi.fn(), request, clearCredential, commitOwner,
  });
  await service.execute({ action: 'initialize' });
  expect(await service.execute({ action: 'logout' })).toMatchObject({ success: true, data: { status: 'signed_out', session: null } });
  expect(request).toHaveBeenLastCalledWith('https://hub.vibelink.cloud', '/api/auth/logout', { token: 'fake-current', body: {} });
  expect(clearCredential).toHaveBeenCalledOnce();
  expect(commitOwner).toHaveBeenLastCalledWith(null);
});

it('rejects expired or replaced saved identities and never falls back to local owner', async () => {
  const commitOwner = vi.fn();
  const readLocalAdmin = vi.fn();
  const clearCredential = vi.fn(async () => undefined);
  const service = new HubAuthService({
    readCredential: async () => ({ origin: 'https://hub.vibelink.cloud', token: 'fake-old', session }),
    request: async () => ({ status: 200, data: { session: { ...session, userId: 'beta_02' } } }),
    readLocalAdmin, commitOwner, clearCredential,
  });
  expect(await service.execute({ action: 'initialize' })).toMatchObject({ success: false, error: { code: 'AUTH_EXPIRED' } });
  expect(clearCredential).toHaveBeenCalled();
  expect(commitOwner).not.toHaveBeenCalled();
  expect(readLocalAdmin).not.toHaveBeenCalled();
});

it('discards an approval that arrives after QR cancellation without writing credentials', async () => {
  let finish!: (value: unknown) => void;
  const pending = new Promise((resolve) => { finish = resolve; });
  const writeCredential = vi.fn();
  const commitOwner = vi.fn();
  const service = new HubAuthService({
    readCredential: async () => null, readLocalAdmin: vi.fn(), ensureVault: async () => undefined,
    writeCredential, commitOwner,
    request: vi.fn(async (_origin: string, path: string) => path.endsWith('/start')
      ? { status: 200, data: { id: 'qr_test', pollSecret: 'fake-poll', link: 'vibelink://login?s=fake-scan', verificationCode: '42', expiresAt: 9999999999999, purpose: 'desktop_login' } }
      : await pending as { status: number; data: unknown }),
  });
  await service.execute({ action: 'startQr', edition: 'beta' });
  const polling = service.pollQr();
  await service.execute({ action: 'cancelQr' });
  finish({ status: 200, data: { status: 'approved', token: 'fake-stale', session } });
  expect(await polling).toMatchObject({ success: true, data: { status: 'signed_out', qr: null } });
  expect(writeCredential).not.toHaveBeenCalled();
  expect(commitOwner).not.toHaveBeenCalled();
});

it('keeps QR polling proof in Main and persists approval before acknowledging it', async () => {
  const order: string[] = [];
  const ports = {
    readCredential: vi.fn(async () => null), readLocalAdmin: vi.fn(),
    ensureVault: vi.fn(async () => undefined),
    writeCredential: vi.fn(async () => { order.push('persist'); }),
    commitOwner: vi.fn(async () => { order.push('owner'); }),
    request: vi.fn(async (_origin: string, path: string) => {
      if (path.endsWith('/start')) return { status: 200, data: { id: 'qr_test', pollSecret: 'fake-private-poll-proof', link: 'vibelink://login?purpose=desktop_login&s=fake-scan-proof', verificationCode: '48 92', expiresAt: 9999999999999, intervalMs: 1000, purpose: 'desktop_login' } };
      if (path.endsWith('/poll')) return { status: 200, data: { status: 'approved', token: 'fake-qr-client', session } };
      order.push('ack'); return { status: 200, data: { ok: true, status: 'consumed' } };
    }),
  };
  const service = new HubAuthService(ports);
  const started = await service.execute({ action: 'startQr', edition: 'beta' });
  expect(JSON.stringify(started)).not.toContain('fake-private-poll-proof');
  expect(started).toMatchObject({ success: true, data: { status: 'signed_out', qr: { verificationCode: '48 92' } } });
  expect(await service.pollQr()).toMatchObject({ success: true, data: { status: 'authenticated', session } });
  expect(order).toEqual(['persist', 'owner', 'ack']);
  expect(ports.request).toHaveBeenCalledWith('https://hub.vibelink.cloud', '/api/auth/qr/ack', { body: { id: 'qr_test', pollSecret: 'fake-private-poll-proof' } });
});

it('retains the old local database only after an explicit localhost admin exchange', async () => {
  const localSession = { ...session, edition: 'dev' as const, userId: 'owner', tenantId: null };
  const ports = {
    readCredential: vi.fn(async () => null), readLocalAdmin: vi.fn(async () => 'fake-local-admin'),
    ensureVault: vi.fn(async () => undefined), writeCredential: vi.fn(async () => undefined),
    trustLocalOwner: vi.fn(async () => undefined),
    request: vi.fn(async () => ({ status: 200, data: { ok: true, token: 'fake-client', session: localSession } })),
    commitOwner: vi.fn(async (_owner: string | null) => undefined),
  };
  const result = await new HubAuthService(ports).execute({ action: 'useLocalWorkspace' });
  expect(result).toMatchObject({ success: true, data: { session: localSession } });
  expect(ports.request).toHaveBeenCalledWith('http://127.0.0.1:18995', '/api/auth/session', { token: 'fake-local-admin', body: { deviceName: 'VibeLink Desktop' } });
  expect(ports.trustLocalOwner).toHaveBeenCalledWith(localSession);
  expect(ports.commitOwner).toHaveBeenCalledWith('local-v1');
});

it('exchanges a beta invite only with the public Hub, persisting before publishing the owner', async () => {
  const order: string[] = [];
  const ports = {
    readCredential: vi.fn(async () => null), readLocalAdmin: vi.fn(),
    ensureVault: vi.fn(async () => { order.push('vault'); }),
    writeCredential: vi.fn(async () => { order.push('persist'); }),
    request: vi.fn(async () => { order.push('network'); return { status: 200, data: { ok: true, token: 'fake-beta-token', session } }; }),
    commitOwner: vi.fn(async () => { order.push('owner'); }),
  };
  const service = new HubAuthService(ports);
  expect(await service.execute({ action: 'activateBeta', code: 'invite_fake' })).toMatchObject({ success: true, data: { session } });
  expect(order).toEqual(['vault', 'network', 'persist', 'owner']);
  expect(ports.request).toHaveBeenCalledWith('https://hub.vibelink.cloud', '/api/auth/beta', { body: { code: 'invite_fake', deviceName: 'VibeLink Desktop' } });
  expect(ports.readLocalAdmin).not.toHaveBeenCalled();
});

it('restores only a server-verified principal and commits its opaque namespace', async () => {
  const ports = {
    readCredential: vi.fn(async () => ({ origin: 'https://hub.vibelink.cloud', token: 'fake-session-token', session })),
    request: vi.fn(async () => ({ status: 200, data: { ok: true, session } })),
    readLocalAdmin: vi.fn(), commitOwner: vi.fn(async (_owner: string | null) => undefined),
  };
  const service = new HubAuthService(ports);
  const result = await service.execute({ action: 'initialize' });
  expect(result).toMatchObject({ success: true, data: { status: 'authenticated', session } });
  expect(ports.request).toHaveBeenCalledWith('https://hub.vibelink.cloud', '/api/auth/me', { token: 'fake-session-token' });
  expect(ports.commitOwner.mock.calls[0]?.[0]).toMatch(/^hub_[a-f0-9]{40}$/);
  expect(JSON.stringify(result)).not.toContain('fake-session-token');
  expect(ports.readLocalAdmin).not.toHaveBeenCalled();
});

