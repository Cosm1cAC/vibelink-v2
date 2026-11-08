import { z } from 'zod';
import {
  WorkRowSchema,
  HubGroupSchema,
  HubBotSchema,
  HubMessageSchema,
  HubWorkInputSchema,
  type HubOutbox,
  type HubWorkInput,
  type HubWorkResult,
} from '../../shared/vibelinkWork.js';

export interface WorkCredential {
  scope: string;
  epoch: string;
  origin: string;
  token: string;
}
export interface WorkDisk {
  entries: HubOutbox[];
  read: Record<string, number>;
}
export interface WorkPorts {
  credential(): WorkCredential | null;
  load(scope: string): WorkDisk;
  save(scope: string, value: WorkDisk): void;
  requestId(): string;
  request(
    credential: WorkCredential,
    endpoint: string,
    body?: unknown,
  ): Promise<{ status: number; data: unknown }>;
}
type ErrorCode = Extract<HubWorkResult, { success: false }>['error']['code'];
class WorkFault extends Error {
  constructor(
    readonly code: ErrorCode,
    message: string,
  ) {
    super(message);
  }
}
const failed = (code: ErrorCode, message: string): HubWorkResult => ({
  success: false,
  error: { code, message, recoverable: !['INVALID_INPUT', 'FORBIDDEN'].includes(code) },
});

/** Main owns delivery only. No model call, local execution or replacement ledger. */
export class HubWorkService {
  private tails = new Map<string, Promise<unknown>>();
  constructor(private readonly ports: WorkPorts) {}
  private check(credential: WorkCredential): void {
    const now = this.ports.credential();
    if (!now || now.scope !== credential.scope || now.epoch !== credential.epoch)
      throw new WorkFault('ACCOUNT_CHANGED', '登录身份已改变');
  }
  private load(scope: string): WorkDisk {
    try {
      return this.ports.load(scope);
    } catch {
      throw new WorkFault('STORAGE_UNAVAILABLE', '无法安全读取待发送记录');
    }
  }
  private save(scope: string, disk: WorkDisk): void {
    try {
      this.ports.save(scope, disk);
    } catch {
      throw new WorkFault('STORAGE_UNAVAILABLE', '无法安全保存待发送记录，原编号保持不变');
    }
  }
  private async transport(credential: WorkCredential, endpoint: string, body?: unknown) {
    this.check(credential);
    let response;
    try {
      response = await this.ports.request(credential, endpoint, body);
    } catch {
      this.check(credential);
      throw new WorkFault('NETWORK_UNAVAILABLE', '连接暂时中断，待发送任务保留原请求编号');
    }
    this.check(credential);
    return response;
  }
  private async request(
    credential: WorkCredential,
    endpoint: string,
    body?: unknown,
  ): Promise<unknown> {
    const response = await this.transport(credential, endpoint, body);
    if (response.status === 401) throw new WorkFault('AUTH_REQUIRED', '登录已失效，请重新登录');
    if (response.status === 403) throw new WorkFault('FORBIDDEN', '当前账号无权执行此操作');
    if (response.status < 200 || response.status >= 300)
      throw new WorkFault(
        response.status === 429 || response.status >= 500 ? 'NETWORK_UNAVAILABLE' : 'HUB_REJECTED',
        `Hub 未确认本次操作（${response.status}），请刷新查看`,
      );
    return response.data;
  }
  private async manageable(
    credential: WorkCredential,
    groupId: string,
    workId: string,
  ): Promise<void> {
    const data = z
      .object({
        work: z.array(z.object({ id: z.string(), canManage: z.boolean().default(false) })),
      })
      .parse(await this.request(credential, `/api/bot-groups/${groupId}/work?archived=true`));
    if (!data.work.some((row) => row.id === workId && row.canManage === true))
      throw new WorkFault('FORBIDDEN', '当前账号无权操作该任务；旧版 Hub 请先升级');
  }
  execute(raw: HubWorkInput): Promise<HubWorkResult> {
    const parsed = HubWorkInputSchema.safeParse(raw);
    if (!parsed.success) return Promise.resolve(failed('INVALID_INPUT', '任务请求无效'));
    const credential = this.ports.credential();
    if (!credential) return Promise.resolve(failed('AUTH_REQUIRED', '请先登录'));
    // Different accounts do not wait for the outgoing account's network call.
    const operation = (this.tails.get(credential.scope) ?? Promise.resolve()).then(() =>
      this.perform(parsed.data, credential),
    );
    this.tails.set(credential.scope, operation);
    void operation.finally(() => {
      if (this.tails.get(credential.scope) === operation) this.tails.delete(credential.scope);
    });
    return operation;
  }
  private async perform(input: HubWorkInput, credential: WorkCredential): Promise<HubWorkResult> {
    let requestId: string | undefined;
    let durable = false;
    let journal: WorkDisk | undefined;
    try {
      this.check(credential);
      const disk = this.load(credential.scope);
      journal = disk;
      switch (input.action) {
        case 'groups':
          return {
            success: true,
            data: {
              kind: 'groups',
              groups: z
                .object({ groups: z.array(HubGroupSchema) })
                .parse(await this.request(credential, '/api/bots')).groups,
            },
          };
        case 'bots':
          return {
            success: true,
            data: {
              kind: 'bots',
              bots: z
                .object({ bots: z.array(HubBotSchema) })
                .parse(await this.request(credential, '/api/bots'))
                .bots.filter((row) => !row.archived),
            },
          };
        case 'createGroup': {
          const bots = z
            .object({ bots: z.array(HubBotSchema) })
            .parse(await this.request(credential, '/api/bots')).bots;
          if (input.botIds.some((id) => !bots.some((bot) => bot.id === id && !bot.archived)))
            return failed('INVALID_INPUT', '请选择当前账号可用的成员');
          const body = {
            id: `group_${this.ports.requestId()}`,
            name: input.name,
            description: '',
            botIds: [...new Set(input.botIds)],
            cwd: '.',
            section: '',
            pinned: false,
            lastText: '',
            lastAt: 0,
          };
          const data = z
            .object({ group: HubGroupSchema })
            .parse(await this.request(credential, '/api/bot-groups', body));
          return { success: true, data: { kind: 'group', group: data.group } };
        }
        case 'list': {
          const query = new URLSearchParams({
            q: input.q ?? '',
            archived: String(input.archived ?? false),
          });
          const rows = z
            .object({ work: z.array(WorkRowSchema) })
            .parse(
              await this.request(credential, `/api/bot-groups/${input.groupId}/work?${query}`),
            ).work;
          return {
            success: true,
            data: {
              kind: 'list',
              work: rows.map((row) => ({
                ...row,
                unread:
                  !!(row.result || row.error || row.status === 'needs_input') &&
                  (disk.read[`${row.groupId}/${row.id}`] ?? 0) < row.updatedAt,
              })),
            },
          };
        }
        case 'messages':
          return {
            success: true,
            data: {
              kind: 'messages',
              messages: z
                .object({ messages: z.array(HubMessageSchema) })
                .parse(await this.request(credential, `/api/bot-groups/${input.groupId}/messages`))
                .messages,
            },
          };
        case 'markRead': {
          const key = `${input.groupId}/${input.workId}`;
          disk.read[key] = Math.max(disk.read[key] ?? 0, input.updatedAt);
          disk.read = Object.fromEntries(
            Object.entries(disk.read)
              .sort((a, b) => b[1] - a[1])
              .slice(0, 2000),
          );
          this.save(credential.scope, disk);
          return { success: true, data: { kind: 'marked' } };
        }
        case 'dismissFailed': {
          const entry = disk.entries.find((row) => row.requestId === input.requestId);
          if (!entry || entry.status !== 'failed')
            return failed('INVALID_INPUT', '只能移除已拒绝的发送记录');
          disk.entries = disk.entries.filter((row) => row.requestId !== input.requestId);
          this.save(credential.scope, disk);
          return { success: true, data: { kind: 'outbox', outbox: disk.entries } };
        }
        case 'manage': {
          await this.manageable(credential, input.groupId, input.workId);
          await this.request(
            credential,
            `/api/bot-groups/${input.groupId}/work/${input.workId}/actions`,
            { action: input.command },
          );
          // Explicit controls never replay an unrelated pending submission.
          return { success: true, data: { kind: 'accepted' } };
        }
        case 'export': {
          await this.manageable(credential, input.groupId, input.workId);
          const exported = await this.request(
            credential,
            `/api/bot-groups/${input.groupId}/work/${input.workId}/export`,
          );
          return {
            success: true,
            data: { kind: 'export', exported: JSON.stringify(exported, null, 2) },
          };
        }
        case 'submit': {
          if (input.replyToWorkId)
            await this.manageable(credential, input.groupId, input.replyToWorkId);
          if (disk.entries.length >= 100)
            return failed('STORAGE_UNAVAILABLE', '待发送记录已达上限，请先处理原任务');
          requestId = this.ports.requestId();
          disk.entries.push({
            requestId,
            groupId: input.groupId,
            text: input.text,
            ...(input.replyToWorkId ? { replyToWorkId: input.replyToWorkId } : {}),
            status: 'pending',
            createdAt: Date.now(),
          });
          this.save(credential.scope, disk);
          durable = true;
          break;
        }
        case 'outbox':
          break;
      }
      for (const entry of disk.entries.filter((row) => row.status === 'pending')) {
        const response = await this.transport(credential, `/api/bot-groups/${entry.groupId}/work`, {
          requestId: entry.requestId,
          text: entry.text,
          mode: 'auto',
          ...(entry.replyToWorkId ? { replyToWorkId: entry.replyToWorkId } : {}),
        });
        if (response.status >= 200 && response.status < 300) {
          const receipt = z.object({ work: z.object({ id: z.string().min(1), groupId: z.literal(entry.groupId) }) }).safeParse(response.data);
          if (!receipt.success) throw new WorkFault('NETWORK_UNAVAILABLE', 'Hub 回执尚未确认，保留原请求编号');
          disk.entries = disk.entries.filter((row) => row.requestId !== entry.requestId);
        } else if (response.status === 429 || response.status === 408 || response.status >= 500) {
          entry.error = `Hub 暂时不可用（${response.status}），仍待发送`;
          this.save(credential.scope, disk);
          break;
        } else if (response.status === 401 || response.status === 403) {
          entry.error = '未能确认原提交状态；请重新登录同一账号，继续沿用原请求编号';
          this.save(credential.scope, disk);
          throw new WorkFault(response.status === 401 ? 'AUTH_REQUIRED' : 'FORBIDDEN', entry.error);
        } else {
          entry.status = 'failed';
          entry.error = `此次发送被 Hub 拒绝（${response.status}）；原提交状态请先核对`;
        }
        this.save(credential.scope, disk);
        if (entry.status === 'failed' && entry.requestId === requestId)
          return failed('HUB_REJECTED', entry.error!);
      }
      return {
        success: true,
        data: {
          kind: requestId ? 'accepted' : 'outbox',
          outbox: disk.entries,
          ...(requestId ? { requestId } : {}),
        },
      };
    } catch (error) {
      const fault =
        error instanceof WorkFault
          ? error
          : new WorkFault('HUB_REJECTED', 'Hub 返回的任务数据无效，请升级后重试');
      // A lost response is pending delivery, not a confirmed Hub task. Returning
      // its saved ID clears the composer; the journal explicitly displays pending.
      if (
        durable &&
        requestId &&
        ['NETWORK_UNAVAILABLE', 'STORAGE_UNAVAILABLE', 'AUTH_REQUIRED', 'FORBIDDEN'].includes(
          fault.code,
        )
      )
        return { success: true, data: { kind: 'accepted', requestId, outbox: journal?.entries } };
      return failed(fault.code, fault.message);
    }
  }
}
