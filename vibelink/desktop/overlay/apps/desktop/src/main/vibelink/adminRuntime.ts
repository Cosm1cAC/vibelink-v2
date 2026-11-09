import { net } from 'electron';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import type { AdminResult } from '../../shared/vibelinkAdmin.js';
import { getActiveAppSession, isAppSessionBoundaryPending, onAppSessionInvalidated } from '../appSessionState.js';
import { currentHubCredential, currentHubCommittedOwner, isVibeLinkMode, onHubAuthChanged } from './authRuntime.js';
import { getVerifiedHubIdentity } from './verifiedIdentity.js';
import { localAdminIdentity } from './adminPolicy.js';
import { HubAdminService, type AdminIdentity } from './adminService.js';

let service: HubAdminService | null = null;
const inflight = new Map<AbortController, AdminIdentity>();
export function currentBetaAdminIdentity(): AdminIdentity | null {
  const current = currentHubCredential(), active = getActiveAppSession();
  return localAdminIdentity({ enabled: isVibeLinkMode(), pending: isAppSessionBoundaryPending(),
    origin: current?.origin ?? '', session: current?.session ?? null, verified: getVerifiedHubIdentity(),
    mode: active.mode, dataOwnerId: active.dataOwnerId, committedOwner: currentHubCommittedOwner(), generation: active.generation }, Date.now());
}
function matches(expected: AdminIdentity): boolean {
  const actual = currentBetaAdminIdentity();
  return !!actual && actual.scope === expected.scope && actual.epoch === expected.epoch;
}
function instance(): HubAdminService {
 if (service) return service;
 onHubAuthChanged(() => { for (const [controller, expected] of inflight) if (!matches(expected)) controller.abort(); });
 onAppSessionInvalidated(() => { for (const controller of inflight.keys()) controller.abort(); });
 service = new HubAdminService({
  identity: currentBetaAdminIdentity,
  readAdmin: async () => {
    if (!currentBetaAdminIdentity()) return null;
    const local = process.env.LOCALAPPDATA || path.join(os.homedir(), 'AppData', 'Local');
    try { const token = (await fs.readFile(path.join(local, 'vibelink-mesh', 'data-beta', 'beta', 'token.txt'), 'utf8')).trim();
      return token.length > 0 && token.length <= 4096 ? token : null;
    } catch { return null; }
  },
  request: async (identity, token, endpoint, body) => {
    const read = body === undefined && (endpoint === '/api/beta/tenants' || endpoint === '/api/usage/requests' || /^\/api\/usage\/users\?day=\d{4}-\d{2}-\d{2}$/.test(endpoint));
    const write = body !== undefined && endpoint === '/api/usage/limits';
    if ((!read && !write) || !matches(identity)) throw new Error('Invalid administrator capability');
    const controller = new AbortController(); inflight.set(controller, identity);
    try {
      const lifetime = Math.max(1, Math.min(10000, (currentHubCredential()?.session.expiresAt ?? 0) - Date.now()));
      const response = await net.fetch('http://127.0.0.1:18998' + endpoint, { method: write ? 'POST' : 'GET', redirect: 'error', credentials: 'omit',
        headers: { Authorization: 'Bearer ' + token, ...(write ? { 'Content-Type': 'application/json' } : {}) },
        ...(write ? { body: JSON.stringify(body) } : {}), signal: AbortSignal.any([controller.signal, AbortSignal.timeout(lifetime)]) });
      const reader = response.body?.getReader(); if (!reader) throw new Error('Missing administrator response');
      const chunks: Uint8Array[] = []; let size = 0;
      try { while (true) { const next = await reader.read(); if (next.done) break;
        size += next.value.byteLength; if (size > 4 * 1024 * 1024) { await reader.cancel(); throw new Error('Administrator response too large'); }
        chunks.push(next.value);
      } } finally { reader.releaseLock(); }
      if (!matches(identity) || controller.signal.aborted) throw new Error('Administrator capability changed');
      return { status: response.status, data: JSON.parse(Buffer.concat(chunks).toString('utf8')) };
    } finally { inflight.delete(controller); }
  },
 });
 return service;
}
export async function invokeHubAdmin(raw: unknown): Promise<AdminResult> {
 if (!isVibeLinkMode()) return { success: false, error: { code: 'AUTH_REQUIRED', message: '请先登录本机开发工作区', recoverable: true } };
 return instance().execute(raw as import('../../shared/vibelinkAdmin.js').AdminInput);
}
