import { net } from 'electron';
import type { AutomationResult } from '../../shared/vibelinkAutomation.js';
import { onAppSessionInvalidated } from '../appSessionState.js';
import { currentHubCredential, isVibeLinkMode, onHubAuthChanged } from './authRuntime.js';
import { currentHubWorkCredential } from './workRuntime.js';
import type { WorkCredential } from './workService.js';
import { HubAutomationService } from './automationService.js';

let service: HubAutomationService | null = null;
const inflight = new Map<AbortController, WorkCredential>();
function matches(expected: WorkCredential): boolean {
  const now = currentHubWorkCredential();
  return (
    !!now &&
    now.scope === expected.scope &&
    now.epoch === expected.epoch &&
    now.origin === expected.origin &&
    now.token === expected.token
  );
}
function instance(): HubAutomationService {
  if (service) return service;
  onHubAuthChanged(() => {
    for (const [controller, identity] of inflight) if (!matches(identity)) controller.abort();
  });
  onAppSessionInvalidated(() => {
    for (const controller of inflight.keys()) controller.abort();
  });
  service = new HubAutomationService({
    credential: currentHubWorkCredential,
    request: async (identity, endpoint, body) => {
      if (
        !matches(identity) ||
        !['http://127.0.0.1:18995', 'https://hub.vibelink.cloud'].includes(identity.origin) ||
        !/^\/api\/bot-groups\/[a-zA-Z0-9_-]{1,128}\/automation$/.test(endpoint)
      )
        throw new Error('Invalid policy capability');
      const controller = new AbortController();
      inflight.set(controller, identity);
      try {
        const lifetime = Math.max(
          1,
          Math.min(10000, (currentHubCredential()?.session.expiresAt ?? 0) - Date.now()),
        );
        const response = await net.fetch(identity.origin + endpoint, {
          method: body === undefined ? 'GET' : 'POST',
          redirect: 'error',
          credentials: 'omit',
          headers: {
            Authorization: 'Bearer ' + identity.token,
            ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
          },
          ...(body === undefined ? {} : { body: JSON.stringify(body) }),
          signal: AbortSignal.any([controller.signal, AbortSignal.timeout(lifetime)]),
        });
        const reader = response.body?.getReader();
        if (!reader) throw new Error('Missing policy response');
        const chunks: Uint8Array[] = [];
        let size = 0;
        try {
          while (true) {
            const next = await reader.read();
            if (next.done) break;
            size += next.value.byteLength;
            if (size > 4 * 1024 * 1024) {
              await reader.cancel();
              throw new Error('Policy response too large');
            }
            chunks.push(next.value);
          }
        } finally {
          reader.releaseLock();
        }
        if (!matches(identity) || controller.signal.aborted)
          throw new Error('Policy capability changed');
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
export async function invokeHubAutomation(raw: unknown): Promise<AutomationResult> {
  if (!isVibeLinkMode())
    return { success: false, error: { code: 'AUTH_REQUIRED', message: '请先登录 Hub 工作区' } };
  return instance().execute(raw as import('../../shared/vibelinkAutomation.js').AutomationInput);
}
