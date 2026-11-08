import { app, BrowserWindow, net, safeStorage } from 'electron';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { HubAuthInputSchema, type HubAuthInput, type HubAuthResult } from '../../shared/vibelinkAuth.js';
import * as authManager from '../authManager.js';
import { HubAuthService, type HubCredential } from './authService.js';
import { HubAuthVault } from './authVault.js';
import { setVerifiedHubIdentity } from './verifiedIdentity.js';
import { getActiveAppSession, isAppSessionBoundaryPending } from '../appSessionState.js';

let service: HubAuthService | null = null;
let initializePromise: Promise<HubAuthResult> | null = null;
let pollTimer: ReturnType<typeof setTimeout> | null = null;
let expiryTimer: ReturnType<typeof setTimeout> | null = null;
let committedHubOwner: string | null = null;
const hubChangeListeners = new Set<() => void>();
export function currentHubCommittedOwner(): string | null { return committedHubOwner; }
export function onHubAuthChanged(listener: () => void): () => void { hubChangeListeners.add(listener); return () => { hubChangeListeners.delete(listener); }; }

function readLocalAdminToken(): string | null {
  const root = process.env.LOCALAPPDATA || path.join(os.homedir(), 'AppData', 'Local');
  for (const pieces of [['data', 'dev', 'token.txt'], ['data', 'token.txt']]) {
    try {
      const token = fs.readFileSync(path.join(root, 'vibelink-mesh', ...pieces), 'utf8').trim();
      if (token) return token;
    } catch { /* Only explicit local workspace and owner-management actions use this. */ }
  }
  return null;
}

export function isVibeLinkMode(): boolean { return process.env.CINDY_VIBELINK_EMBEDDED === '1'; }

function instance(): HubAuthService {
  if (service) return service;
  const vault = new HubAuthVault(path.join(app.getPath('userData'), 'safe-storage', 'vibelink-hub-session-v1.enc'), safeStorage);
  service = new HubAuthService({
    readCredential: () => vault.readCredential(), writeCredential: (value) => vault.writeCredential(value),
    clearCredential: () => vault.clearCredential(), ensureVault: () => vault.ensureAvailable(),
    trustLocalOwner: (session) => vault.trustLocalOwner(session),
    isTrustedLocalOwner: (session) => vault.isTrustedLocalOwner(session),
    revokeCapability: () => setVerifiedHubIdentity(null),
    onStateChanged: () => { publish({ success: true, data: instance().getSnapshot() }); },
    commitOwner: async (owner) => {
      setVerifiedHubIdentity(null);
      committedHubOwner = null;
      await authManager.commitHubOwner(owner);
      committedHubOwner = owner;
    },
    readLocalAdmin: async () => readLocalAdminToken(),
    request: async (origin, endpoint, request) => {
      // Neither the renderer nor a server response chooses credential destinations.
      if (!['http://127.0.0.1:18995', 'https://hub.vibelink.cloud'].includes(origin) || !endpoint.startsWith('/api/auth/')) throw new Error('Invalid Hub authentication destination');
      const response = await net.fetch(`${origin}${endpoint}`, {
        method: request.body ? 'POST' : 'GET', redirect: 'error',
        credentials: 'omit',
        headers: { ...(request.token ? { Authorization: `Bearer ${request.token}` } : {}), ...(request.body ? { 'Content-Type': 'application/json' } : {}) },
        ...(request.body ? { body: JSON.stringify(request.body) } : {}),
        signal: AbortSignal.timeout(10000),
      });
      return { status: response.status, data: await response.json() };
    },
  });
  return service;
}

function publish(result: HubAuthResult): HubAuthResult {
  const credential = instance().getCredential();
  setVerifiedHubIdentity(credential?.session ?? null);
  for (const listener of hubChangeListeners) listener();
  // A delayed caller never publishes the snapshot captured before logout/B login.
  if (result.success) result = { success: true, data: instance().getSnapshot() };
  if (expiryTimer) clearTimeout(expiryTimer);
  expiryTimer = null;
  if (credential) {
    expiryTimer = setTimeout(() => { void instance().expireIfNeeded().then(publish); }, Math.min(2_147_483_647, Math.max(0, credential.session.expiresAt - Date.now())));
    expiryTimer.unref();
  }
  for (const window of BrowserWindow.getAllWindows()) {
    if (!window.isDestroyed()) window.webContents.send('vibelink:auth-state', result);
  }
  return result;
}

function stopPolling(): void {
  if (pollTimer) clearTimeout(pollTimer);
  pollTimer = null;
}

function schedulePoll(): void {
  stopPolling();
  if (!instance().hasPendingQr()) return;
  pollTimer = setTimeout(async () => {
    publish(await instance().pollQr());
    schedulePoll();
  }, 1500);
  pollTimer.unref();
}

export async function invokeHubAuth(raw: unknown): Promise<HubAuthResult> {
  if (!isVibeLinkMode()) throw new Error('VibeLink desktop mode is not enabled');
  const parsed = HubAuthInputSchema.safeParse(raw);
  if (!parsed.success) return { success: false, error: { code: 'INVALID_INPUT', message: '登录请求无效', recoverable: true } };
  const input: HubAuthInput = parsed.data;
  await instance().expireIfNeeded();
  if (input.action === 'snapshot') return { success: true, data: instance().getSnapshot() };
  if (input.action === 'initialize') {
    if (instance().getSnapshot().status === 'authenticated') return { success: true, data: instance().getSnapshot() };
    if (!initializePromise) initializePromise = instance().execute(input).then(publish).finally(() => { initializePromise = null; });
    return initializePromise;
  }
  stopPolling();
  const result = publish(await instance().execute(input));
  schedulePoll();
  return result;
}

/** Main-only authenticated transport capability. No admin fallback. */
export function currentHubCredential(): HubCredential | null { return service?.getCredential() ?? null; }

export async function currentHubControlSettings(management: boolean): Promise<{ hub: string; token: string; localManagement: boolean } | null> {
  const credential = currentHubCredential();
  if (!credential || isAppSessionBoundaryPending()) return null;
  const localManagement = credential.origin === 'http://127.0.0.1:18995' && credential.session.edition === 'dev'
    && credential.session.userId === 'owner' && getActiveAppSession().dataOwnerId === 'local-v1';
  if (management && !localManagement) return null;
  const token = management ? readLocalAdminToken() : credential.token;
  return token ? { hub: credential.origin, token, localManagement } : null;
}

export async function initializeHubAppAuth(): Promise<authManager.AuthState> {
  await invokeHubAuth({ action: 'initialize' });
  return authManager.getAuthState();
}
