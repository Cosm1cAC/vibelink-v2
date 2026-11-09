import { app, net, Notification, safeStorage } from 'electron';
import path from 'node:path';
import { z } from 'zod';
import { HubGroupSchema, WorkRowSchema } from '../../shared/vibelinkWork.js';
import {
  NotificationInputSchema,
  type NotificationResult,
} from '../../shared/vibelinkNotifications.js';
import { getDeepLinkMainWindow } from '../deepLink.js';
import { t } from '../i18n.js';
import { onAppSessionInvalidated } from '../appSessionState.js';
import { currentHubCredential, isVibeLinkMode, onHubAuthChanged } from './authRuntime.js';
import { currentHubWorkCredential } from './workRuntime.js';
import type { WorkCredential } from './workService.js';
import { HubNotificationService, type NotificationWork } from './notificationService.js';
import { HubNotificationStore } from './notificationStore.js';
import { createOSNotificationAdapter } from './notificationOS.js';
import { hubNotificationCapability } from './notificationWindows.js';

let service: HubNotificationService | null = null,
  timer: ReturnType<typeof setTimeout> | null = null;
let initialized = false,
  stopped = false,
  polling = false,
  binding = '';
function current(c: WorkCredential): boolean {
  const now = currentHubWorkCredential();
  return !!now && now.scope === c.scope && now.epoch === c.epoch;
}
async function read(c: WorkCredential, endpoint: string, signal: AbortSignal): Promise<unknown> {
  if (
    !current(c) ||
    signal.aborted ||
    !['http://127.0.0.1:18995', 'https://hub.vibelink.cloud'].includes(c.origin)
  )
    throw new Error('Account changed');
  if (endpoint !== '/api/bots' && !/^\/api\/bot-groups\/[a-zA-Z0-9_-]+\/work$/.test(endpoint))
    throw new Error('Invalid notification projection');
  const response = await net.fetch(c.origin + endpoint, {
    method: 'GET',
    redirect: 'error',
    credentials: 'omit',
    headers: { Authorization: `Bearer ${c.token}` },
    signal: AbortSignal.any([signal, AbortSignal.timeout(10000)]),
  });
  if (!response.ok) throw new Error('Notification projection unavailable');
  const reader = response.body?.getReader();
  if (!reader) throw new Error('Missing response');
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const next = await reader.read();
      if (next.done) break;
      size += next.value.byteLength;
      if (size > 16 * 1024 * 1024) {
        await reader.cancel();
        throw new Error('Response limit');
      }
      chunks.push(next.value);
    }
  } finally {
    reader.releaseLock();
  }
  if (!current(c) || signal.aborted) throw new Error('Account changed');
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}
function instance(): HubNotificationService {
  if (service) return service;
  const store = new HubNotificationStore(
    path.join(app.getPath('userData'), 'safe-storage', 'vibelink-notifications-v1'),
    safeStorage,
  );
  service = new HubNotificationService({
    credential: currentHubWorkCredential,
    load: (scope) => store.load(scope),
    save: (scope, disk) => store.save(scope, disk),
    disable: (scope) => store.disable(scope),
    now: Date.now,
    active: () => {
      const window = getDeepLinkMainWindow();
      return !!window && window.isFocused() && window.isVisible() && !window.isMinimized();
    },
    scan: async (c, signal) => {
      const bounded = AbortSignal.any([signal, AbortSignal.timeout(25000)]);
      const groups = z
        .object({ groups: z.array(HubGroupSchema).max(100) })
        .parse(await read(c, '/api/bots', bounded)).groups;
      const rows: NotificationWork[] = [];
      for (const group of groups) {
        const work = z
          .object({ work: z.array(WorkRowSchema).max(500) })
          .parse(await read(c, `/api/bot-groups/${group.id}/work`, bounded)).work;
        rows.push(...work);
        if (rows.length > 500) throw new Error('Projection limit');
      }
      return rows;
    },
    content: (entry, preview) =>
      preview && entry.preview
        ? entry.preview
        : { title: 'VibeLink', body: t('vibelinkNotifications.genericBody') },
    adapter: createOSNotificationAdapter({
      supported: () => hubNotificationCapability().supported,
      registered: () => hubNotificationCapability().registered,
      create: (content) => new Notification({ ...content, silent: true }),
    }),
    navigate: (c, target) => {
      if (!current(c)) return;
      const session = currentHubCredential()?.session;
      const window = getDeepLinkMainWindow();
      if (!session || !window || window.isDestroyed()) return;
      window.show();
      window.focus();
      window.webContents.send('vibelink:notification-open', {
        issuer: session.issuer,
        edition: session.edition,
        userId: session.userId,
        ...target,
      });
    },
  });
  return service;
}
function stopTimer(): void {
  if (timer) clearTimeout(timer);
  timer = null;
}
function schedule(delay: number): void {
  if (stopped || polling || timer) return;
  timer = setTimeout(() => {
    timer = null;
    polling = true;
    void instance()
      .poll()
      .finally(() => {
        polling = false;
        void reconcile(30000);
      });
  }, delay);
  timer.unref();
}
async function reconcile(delay = 0): Promise<void> {
  if (stopped) return;
  const c = currentHubWorkCredential();
  const next = c ? `${c.scope}/${c.epoch}` : '';
  if (next !== binding) {
    stopTimer();
    instance().invalidate();
    binding = next;
  }
  if (!c) return;
  const result = await instance().execute({ action: 'snapshot' });
  if (!current(c) || stopped) return;
  if (result.success && result.data.settings.enabled) schedule(delay);
  else stopTimer();
}
export function startHubNotifications(): void {
  if (initialized || !isVibeLinkMode()) return;
  initialized = true;
  onHubAuthChanged(() => {
    void reconcile();
  });
  onAppSessionInvalidated(() => {
    stopTimer();
    instance().invalidate();
    binding = '';
  });
  app.once('before-quit', () => {
    stopped = true;
    stopTimer();
    instance().invalidate();
  });
  void reconcile();
}
export async function invokeHubNotifications(raw: unknown): Promise<NotificationResult> {
  if (!isVibeLinkMode())
    return {
      success: false,
      error: { code: 'AUTH_REQUIRED', message: 'VibeLink 工作区未启用', recoverable: false },
    };
  const parsed = NotificationInputSchema.safeParse(raw);
  if (!parsed.success)
    return {
      success: false,
      error: { code: 'INVALID_INPUT', message: '提醒请求无效', recoverable: false },
    };
  if (parsed.data.action === 'configure' && !parsed.data.enabled) stopTimer();
  const result = await instance().execute(parsed.data);
  if (parsed.data.action !== 'snapshot') void reconcile();
  return result.success
    ? { success: true, data: { ...result.data, capability: hubNotificationCapability() } }
    : result;
}
