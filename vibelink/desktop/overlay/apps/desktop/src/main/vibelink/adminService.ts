import { z } from 'zod';
import { AdminInputSchema, AdminSeatSchema, AdminUsageSchema, AdminRequestSchema, type AdminInput, type AdminResult } from '../../shared/vibelinkAdmin';
export interface AdminIdentity { scope: string; epoch: string }
export interface AdminResponse { status: number; data: unknown }
export interface AdminPorts {
  identity(): AdminIdentity | null;
  readAdmin(): Promise<string | null>;
  request(identity: AdminIdentity, token: string, endpoint: string, body?: Record<string, unknown>): Promise<AdminResponse>;
}
type Code = Extract<AdminResult, { success: false }>['error']['code'];
const messages: Record<Code, string> = {
  INVALID_INPUT: '管理请求无效', AUTH_REQUIRED: '请先登录本机开发工作区', FORBIDDEN: '当前身份无权访问内测管理',
  ACCOUNT_CHANGED: '登录身份已变化，请重新打开管理页', NETWORK_UNAVAILABLE: '无法连接本机内测 Hub，请检查服务后重试',
  CREDENTIAL_UNAVAILABLE: '本机内测管理凭据不可用，请检查内测 Hub', HUB_REJECTED: '内测 Hub 暂时无法完成此请求，请刷新',
  OUTCOME_UNKNOWN: '保存结果未确认，请刷新核对后再操作', CONFLICT: '额度已被更新，请刷新后再保存',
};
class Fault extends Error { constructor(readonly code: Code) { super(code); } }
export class HubAdminService {
  constructor(private readonly ports: AdminPorts) {}
  private current(identity: AdminIdentity): boolean {
    const now = this.ports.identity();
    return !!now && now.scope === identity.scope && now.epoch === identity.epoch;
  }
  private requireCurrent(identity: AdminIdentity): void {
    if (!this.current(identity)) throw new Fault('ACCOUNT_CHANGED');
  }
  private async read(identity: AdminIdentity, endpoint: string): Promise<unknown> {
    this.requireCurrent(identity);
    const token = await this.ports.readAdmin();
    this.requireCurrent(identity);
    if (!token) throw new Fault('CREDENTIAL_UNAVAILABLE');
    const response = await this.ports.request(identity, token, endpoint);
    this.requireCurrent(identity);
    if (response.status === 401 || response.status === 403) throw new Fault('FORBIDDEN');
    if (response.status !== 200) throw new Fault('HUB_REJECTED');
    return response.data;
  }
  private async seats(identity: AdminIdentity) {
    return z.object({ betaMode: z.literal(true), tenants: z.array(AdminSeatSchema).max(1000) })
      .parse(await this.read(identity, '/api/beta/tenants')).tenants;
  }
  async execute(raw: AdminInput): Promise<AdminResult> {
    try {
      const parsed = AdminInputSchema.safeParse(raw);
      if (!parsed.success) throw new Fault('INVALID_INPUT');
      const input = parsed.data, identity = this.ports.identity();
      if (input.action === 'capability') return { success: true, data: { kind: 'capability', allowed: !!identity, reason: identity ? '' : '仅本机开发者管理员可访问' } };
      if (!identity) throw new Fault('AUTH_REQUIRED');
      if (input.action === 'snapshot') {
        const seats = await this.seats(identity);
        const data = z.object({ day: z.literal(input.day), users: z.array(AdminUsageSchema).max(2000) })
          .parse(await this.read(identity, '/api/usage/users?day=' + input.day));
        this.requireCurrent(identity);
        return { success: true, data: { kind: 'snapshot', day: input.day, seats, users: data.users } };
      }
      if (input.action === 'requests') {
        await this.seats(identity);
        const data = z.object({ requests: z.array(AdminRequestSchema).max(200) })
          .parse(await this.read(identity, '/api/usage/requests'));
        this.requireCurrent(identity);
        return { success: true, data: { kind: 'requests', requests: data.requests } };
      }
      if (input.action === 'setLimits') {
        const seats = await this.seats(identity);
        const seat = seats.find((row) => row.tenantId === input.userId);
        if (!seat) throw new Fault('FORBIDDEN');
        if (seat.limitsRevision !== input.expectedRevision) throw new Fault('CONFLICT');
        this.requireCurrent(identity);
        const token = await this.ports.readAdmin();
        this.requireCurrent(identity);
        if (!token) throw new Fault('CREDENTIAL_UNAVAILABLE');
        let response: AdminResponse;
        try { response = await this.ports.request(identity, token, '/api/usage/limits', { userId: input.userId, limits: input.limits, expectedRevision: input.expectedRevision }); }
        catch { throw new Fault('OUTCOME_UNKNOWN'); }
        if (!this.current(identity)) throw new Fault('OUTCOME_UNKNOWN');
        if (response.status === 409) throw new Fault('CONFLICT');
        if (response.status === 401 || response.status === 403) throw new Fault('FORBIDDEN');
        if (response.status >= 500) throw new Fault('OUTCOME_UNKNOWN');
        if (response.status !== 200) throw new Fault('HUB_REJECTED');
        const receipt = z.object({ ok: z.literal(true), limitsRevision: z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER) }).safeParse(response.data);
        if (!receipt.success || receipt.data.limitsRevision !== input.expectedRevision + 1) throw new Fault('OUTCOME_UNKNOWN');
        return { success: true, data: { kind: 'limitsSaved', userId: input.userId, limits: input.limits, limitsRevision: receipt.data.limitsRevision } };
      }
      throw new Fault('INVALID_INPUT');
    } catch (error) {
      const code = error instanceof Fault ? error.code : error instanceof z.ZodError ? 'HUB_REJECTED' : 'NETWORK_UNAVAILABLE';
      return { success: false, error: { code, message: messages[code], recoverable: true } };
    }
  }
}
