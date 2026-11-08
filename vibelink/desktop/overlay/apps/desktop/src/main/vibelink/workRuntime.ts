import { app, net, safeStorage } from 'electron';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import type { HubWorkResult } from '../../shared/vibelinkWork.js';
import {
  getActiveAppSession,
  isAppSessionBoundaryPending,
  onAppSessionInvalidated,
} from '../appSessionState.js';
import {
  currentHubCredential,
  currentHubCommittedOwner,
  isVibeLinkMode,
  onHubAuthChanged,
} from './authRuntime.js';
import { hubDataOwnerId } from './authService.js';
import { getVerifiedHubIdentity } from './verifiedIdentity.js';
import { HubWorkService, type WorkCredential } from './workService.js';
import { HubWorkStore } from './workStore.js';

let service: HubWorkService | null = null;
const inflight = new Map<AbortController, WorkCredential>();

function credential(): WorkCredential | null {
  const current = currentHubCredential();
  const active = getActiveAppSession();
  if (
    !current ||
    isAppSessionBoundaryPending() ||
    current.session.expiresAt <= Date.now() ||
    getVerifiedHubIdentity()?.sessionId !== current.session.sessionId ||
    active.mode !== 'hub' ||
    !currentHubCommittedOwner() ||
    active.dataOwnerId !== currentHubCommittedOwner()
  )
    return null;
  return {
    scope: hubDataOwnerId(current.session),
    epoch: `${current.session.sessionId}/${active.generation}`,
    origin: current.origin,
    token: current.token,
  };
}

function instance(): HubWorkService {
  if (service) return service;
  const store = new HubWorkStore(
    path.join(app.getPath('userData'), 'safe-storage', 'vibelink-work-v1'),
    safeStorage,
  );
  onHubAuthChanged(() => {
    const current = credential();
    for (const [controller, previous] of inflight) {
      if (!current || current.scope !== previous.scope || current.epoch !== previous.epoch)
        controller.abort();
    }
  });
  onAppSessionInvalidated(() => {
    for (const controller of inflight.keys()) controller.abort();
  });
  service = new HubWorkService({
    credential,
    load: (scope) => store.load(scope),
    save: (scope, disk) => store.save(scope, disk),
    requestId: randomUUID,
    request: async (identity, endpoint, body) => {
      if (!['http://127.0.0.1:18995', 'https://hub.vibelink.cloud'].includes(identity.origin))
        throw new Error('Invalid Hub destination');
      const url = new URL(endpoint, identity.origin);
      const validPath =
        url.pathname === '/api/bots' ||
        url.pathname === '/api/bot-groups' ||
        /^\/api\/bot-groups\/[a-zA-Z0-9_-]+\/(?:messages|work(?:\/[a-zA-Z0-9_-]+\/(?:actions|export))?)$/.test(
          url.pathname,
        );
      if (url.origin !== identity.origin || !validPath) throw new Error('Invalid work endpoint');
      const controller = new AbortController();
      inflight.set(controller, identity);
      try {
        const response = await net.fetch(url.toString(), {
          method: body === undefined ? 'GET' : 'POST',
          redirect: 'error',
          credentials: 'omit',
          headers: {
            Authorization: `Bearer ${identity.token}`,
            ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
          },
          ...(body === undefined ? {} : { body: JSON.stringify(body) }),
          signal: AbortSignal.any([controller.signal, AbortSignal.timeout(15000)]),
        });
        const reader = response.body?.getReader();
        if (!reader) throw new Error('Missing Hub body');
        const chunks: Uint8Array[] = [];
        let size = 0;
        try {
          while (true) {
            const next = await reader.read();
            if (next.done) break;
            size += next.value.byteLength;
            if (size > 16 * 1024 * 1024) {
              await reader.cancel();
              throw new Error('Hub response exceeds limit');
            }
            chunks.push(next.value);
          }
        } finally {
          reader.releaseLock();
        }
        return {
          status: response.status,
          data: JSON.parse(Buffer.concat(chunks).toString('utf8')),
        };
      } finally {
        inflight.delete(controller);
      }
    },
  });
  return service;
}

export async function invokeHubWork(raw: unknown): Promise<HubWorkResult> {
  if (!isVibeLinkMode())
    return {
      success: false,
      error: { code: 'AUTH_REQUIRED', message: 'VibeLink 工作区未启用', recoverable: false },
    };
  return instance().execute(raw as import('../../shared/vibelinkWork.js').HubWorkInput);
}
