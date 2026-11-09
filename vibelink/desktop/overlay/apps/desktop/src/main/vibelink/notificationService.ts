import {
  NotificationInputSchema,
  type NotificationInput,
  type NotificationResult,
  type NotificationEntry,
} from '../../shared/vibelinkNotifications.js';
import type { WorkCredential } from './workService.js';
import { createHash } from 'node:crypto';
export interface NotificationDisk {
  settings: { enabled: boolean; preview: boolean };
  baselined: boolean;
  seen: Record<string, string>;
  entries: NotificationEntry[];
  error: string;
}
export const emptyNotificationDisk = (): NotificationDisk => ({
  settings: { enabled: false, preview: false },
  baselined: false,
  seen: {},
  entries: [],
  error: '',
});
export interface NotificationWork {
  id: string;
  groupId: string;
  updatedAt: number;
  status: string;
  canManage: boolean;
  title: string;
  result?: string;
  error?: string;
}
export interface NotificationHandle {
  show(): void;
  close(): void;
}
export interface NotificationHandlers {
  show(): void;
  click(): void;
  close(): void;
  error(): void;
}
export interface OSNotificationContent {
  title: string;
  body: string;
  id: string;
  groupId: string;
}
export interface NotificationPorts {
  credential(): WorkCredential | null;
  load(scope: string): NotificationDisk;
  save(scope: string, disk: NotificationDisk): void;
  disable?(scope: string): void;
  scan(credential: WorkCredential, signal: AbortSignal): Promise<NotificationWork[]>;
  active(): boolean;
  now(): number;
  adapter: {
    create(content: OSNotificationContent, handlers: NotificationHandlers): NotificationHandle;
  };
  content?(entry: NotificationEntry, preview: boolean): { title: string; body: string };
  navigate(credential: WorkCredential, target: { groupId: string; workId: string }): void;
}
const key = (work: NotificationWork) =>
  `${work.groupId}/${work.id}/${work.updatedAt}/${work.status}`;
type ErrorCode = Extract<NotificationResult, { success: false }>['error']['code'];
class NotificationFault extends Error {
  constructor(
    readonly code: ErrorCode,
    message: string,
  ) {
    super(message);
  }
}
export class HubNotificationService {
  private sequence = 0;
  private loaded = new Set<string>();
  private blockedScopes = new Set<string>();
  private tails = new Map<string, Promise<unknown>>();
  private scans = new Set<AbortController>();
  private handles = new Map<string, NotificationHandle>();
  constructor(private readonly ports: NotificationPorts) {}
  private current(credential: WorkCredential): boolean {
    const now = this.ports.credential();
    return !!now && now.scope === credential.scope && now.epoch === credential.epoch;
  }
  invalidate(): void {
    this.sequence++;
    for (const scan of this.scans) scan.abort();
    this.scans.clear();
    for (const handle of this.handles.values()) {
      try {
        handle.close();
      } catch {
        /* Already closed. */
      }
    }
    this.handles.clear();
    this.loaded.clear();
  }
  private check(c: WorkCredential, sequence: number): void {
    if (!this.current(c) || sequence !== this.sequence)
      throw new NotificationFault('ACCOUNT_CHANGED', '登录身份或提醒设置已改变');
  }
  private serial<T>(c: WorkCredential, operation: () => T | Promise<T>): Promise<T> {
    const result = (this.tails.get(c.scope) ?? Promise.resolve()).then(operation);
    const settled = result.then(
      () => {},
      () => {},
    );
    this.tails.set(c.scope, settled);
    void settled.then(() => {
      if (this.tails.get(c.scope) === settled) this.tails.delete(c.scope);
    });
    return result;
  }
  private save(c: WorkCredential, disk: NotificationDisk): void {
    try {
      this.ports.save(c.scope, disk);
    } catch {
      throw new NotificationFault('STORAGE_UNAVAILABLE', '提醒记录未能安全保存，未继续投递');
    }
  }
  private load(c: WorkCredential): NotificationDisk {
    let disk: NotificationDisk;
    try {
      disk = this.ports.load(c.scope);
    } catch {
      throw new NotificationFault('STORAGE_UNAVAILABLE', '无法读取安全提醒记录');
    }
    let changed = false;
    for (const row of disk.entries)
      if (
        row.state === 'dispatching' &&
        (!this.loaded.has(c.scope) || this.ports.now() - row.attemptedAt > 30000)
      ) {
        row.state = 'unknown';
        row.error = '上次系统投递结果未确认；仅可显式重发提醒';
        changed = true;
      }
    if (changed) this.save(c, disk);
    this.loaded.add(c.scope);
    return disk;
  }
  private receipt(
    c: WorkCredential,
    sequence: number,
    id: string,
    attempt: number,
    type: 'show' | 'click' | 'close' | 'error',
  ): void {
    void this.serial(c, () => {
      this.check(c, sequence);
      const disk = this.load(c);
      const row = disk.entries.find((e) => e.id === id);
      if (
        !row ||
        row.attempt !== attempt ||
        row.receipts.some((r) => r.type === type && r.attempt === attempt)
      )
        return;
      if (!disk.settings.enabled && type !== 'close') return;
      row.receipts.push({ type, at: this.ports.now(), attempt });
      row.receipts = row.receipts.slice(-20);
      if (type === 'show' || type === 'click') {
        row.state = 'delivered';
        row.error = '';
      } else if (type === 'error') {
        row.state = row.receipts.some((r) => r.type === 'show' && r.attempt === attempt)
          ? 'unknown'
          : 'failed';
        row.error = '系统未确认提醒投递';
      } else if (row.state === 'dispatching') {
        row.state = 'unknown';
        row.error = '提醒已关闭，但未收到系统显示回执';
      }
      this.save(c, disk);
      if (type === 'click') {
        this.check(c, sequence);
        this.ports.navigate(c, { groupId: row.groupId, workId: row.workId });
      }
      if (type === 'close' || type === 'error') this.handles.delete(`${c.scope}/${id}`);
    }).catch(() => {
      /* Fail closed: the durable dispatching state recovers as unknown. */
    });
  }
  private dispatch(
    c: WorkCredential,
    sequence: number,
    disk: NotificationDisk,
    row: NotificationEntry,
    explicit = false,
  ): void {
    this.check(c, sequence);
    if (!explicit && this.ports.active()) {
      row.state = 'suppressed';
      row.error = '应用当前在前台，保留本机未读，不补弹旧提醒';
      this.save(c, disk);
      return;
    }
    const handleKey = `${c.scope}/${row.id}`;
    try {
      this.handles.get(handleKey)?.close();
    } catch {
      /* Old attempt cannot affect this one. */
    }
    row.state = 'dispatching';
    row.attempt++;
    row.attemptedAt = this.ports.now();
    row.error = '';
    this.save(c, disk); // Crash after this point is uncertain, never an automatic resend.
    const attempt = row.attempt;
    const handlers = Object.fromEntries(
      ['show', 'click', 'close', 'error'].map((type) => [
        type,
        () =>
          this.receipt(c, sequence, row.id, attempt, type as 'show' | 'click' | 'close' | 'error'),
      ]),
    ) as unknown as NotificationHandlers;
    let handle: NotificationHandle;
    try {
      handle = this.ports.adapter.create(
        {
          ...(this.ports.content?.(row, disk.settings.preview) ??
            (disk.settings.preview && row.preview
              ? row.preview
              : { title: 'VibeLink', body: '任务状态已更新，请打开 Hub 工作区查看。' })),
          id: row.id,
          groupId: createHash('sha256').update(c.scope).digest('hex'),
        },
        handlers,
      );
    } catch {
      row.state = 'failed';
      row.error = '系统提醒能力不可用，未调用显示';
      this.save(c, disk);
      return;
    }
    try {
      this.check(c, sequence);
    } catch (error) {
      handle.close();
      throw error;
    }
    this.handles.set(handleKey, handle);
    try {
      handle.show();
    } catch {
      row.state = 'unknown';
      row.error = '显示调用结果未确认；不会自动重发提醒';
      this.save(c, disk);
    }
  }
  async execute(input: NotificationInput): Promise<NotificationResult> {
    const parsed = NotificationInputSchema.safeParse(input);
    if (!parsed.success)
      return {
        success: false,
        error: { code: 'INVALID_INPUT', message: '提醒请求无效', recoverable: false },
      };
    const credential = this.ports.credential();
    if (!credential)
      return {
        success: false,
        error: { code: 'AUTH_REQUIRED', message: '请先登录', recoverable: true },
      };
    if (parsed.data.action === 'configure' && !parsed.data.enabled) {
      this.blockedScopes.add(credential.scope);
      this.invalidate();
      try {
        this.ports.disable?.(credential.scope);
      } catch {
        return {
          success: false,
          error: {
            code: 'STORAGE_UNAVAILABLE',
            message: '当前提醒已停用，但关闭设置未能持久保存',
            recoverable: true,
          },
        };
      }
    }
    const command = parsed.data;
    const sequence = this.sequence;
    try {
      return await this.serial(credential, () => {
        this.check(credential, sequence);
        const disk = this.load(credential);
        if (this.blockedScopes.has(credential.scope)) disk.settings.enabled = false;
        if (command.action === 'configure') {
          if (!command.enabled || disk.settings.enabled !== command.enabled) {
            disk.baselined = false;
            disk.seen = {};
            for (const row of disk.entries)
              if (row.state === 'pending') {
                row.state = 'suppressed';
                row.error = '设置已改变，重新启用后不补发历史提醒';
              }
          }
          disk.settings = { enabled: command.enabled, preview: command.preview };
          if (!disk.settings.preview) for (const row of disk.entries) row.preview = null;
          this.save(credential, disk);
          if (command.enabled) this.blockedScopes.delete(credential.scope);
        } else if (command.action === 'resend') {
          if (!disk.settings.enabled)
            throw new NotificationFault('NOT_ENABLED', '请先显式开启系统提醒');
          const row = disk.entries.find((e) => e.id === command.id);
          if (!row) throw new NotificationFault('NOT_FOUND', '提醒记录不存在');
          if (row.state === 'dispatching')
            throw new NotificationFault('NOT_ENABLED', '上次投递尚待确认，请稍后查看回执');
          this.dispatch(credential, sequence, disk, row, true);
        }
        return { success: true as const, data: { ...disk, scope: credential.scope } };
      });
    } catch (error) {
      const fault =
        error instanceof NotificationFault
          ? error
          : new NotificationFault('STORAGE_UNAVAILABLE', '提醒记录不可用');
      return {
        success: false,
        error: { code: fault.code, message: fault.message, recoverable: true },
      };
    }
  }
  async poll(): Promise<void> {
    const credential = this.ports.credential();
    if (!credential) return;
    const sequence = this.sequence;
    await this.serial(credential, async () => {
      this.check(credential, sequence);
      if (this.blockedScopes.has(credential.scope)) return;
      const disk = this.load(credential);
      if (!disk.settings.enabled) return;
      const controller = new AbortController();
      this.scans.add(controller);
      let rows: NotificationWork[];
      try {
        rows = await this.ports.scan(credential, controller.signal);
      } finally {
        this.scans.delete(controller);
      }
      this.check(credential, sequence);
      disk.error = '';
      if (!disk.baselined) {
        for (const row of rows) disk.seen[`${row.groupId}/${row.id}`] = key(row);
        disk.baselined = true;
        this.ports.save(credential.scope, disk);
        return;
      }
      for (const row of rows) {
        const eventKey = key(row),
          workKey = `${row.groupId}/${row.id}`;
        if (disk.seen[workKey] === eventKey) continue;
        if (!row.canManage || !['completed', 'needs_input', 'failed'].includes(row.status)) {
          disk.seen[workKey] = eventKey;
          continue;
        }
        while (disk.entries.length >= 500) {
          const old = disk.entries.findIndex((e) => !['pending', 'dispatching'].includes(e.state));
          if (old < 0) break;
          disk.entries.splice(old, 1);
        }
        if (disk.entries.length >= 500) continue;
        disk.seen[workKey] = eventKey;
        disk.entries.push({
          id: createHash('sha256').update(`${credential.scope}\0${eventKey}`).digest('hex'),
          key: eventKey,
          groupId: row.groupId,
          workId: row.id,
          eventStatus: row.status as NotificationEntry['eventStatus'],
          updatedAt: row.updatedAt,
          createdAt: this.ports.now(),
          state: 'pending',
          attempt: 0,
          attemptedAt: 0,
          error: '',
          receipts: [],
          preview: disk.settings.preview
            ? {
                title: row.title.slice(0, 140),
                body: (row.result || row.error || '').slice(0, 600),
              }
            : null,
        });
      }
      const observed = new Set(rows.map((row) => `${row.groupId}/${row.id}`));
      disk.seen = Object.fromEntries([
        ...Object.entries(disk.seen)
          .filter(([id]) => !observed.has(id))
          .slice(-4500),
        ...Object.entries(disk.seen).filter(([id]) => observed.has(id)),
      ]);
      this.save(credential, disk);
      for (const entry of disk.entries.filter((row) => row.state === 'pending').slice(0, 3)) {
        this.dispatch(credential, sequence, disk, entry);
      }
    }).catch(() => {
      if (!this.current(credential) || sequence !== this.sequence) return;
      try {
        const disk = this.load(credential);
        if (disk.settings.enabled) {
          disk.error = '提醒同步未完成；未确认的投递不会自动重发';
          this.save(credential, disk);
        }
      } catch {
        /* No unhandled background failures or plaintext fallback. */
      }
    });
  }
}
