import { z } from 'zod';
import {
  AutomationInputSchema,
  AutomationViewSchema,
  type AutomationInput,
  type AutomationResult,
} from '../../shared/vibelinkAutomation';
import type { WorkCredential } from './workService';
export interface AutomationPorts {
  credential(): WorkCredential | null;
  request(
    identity: WorkCredential,
    endpoint: string,
    body?: Record<string, unknown>,
  ): Promise<{ status: number; data: unknown }>;
}
type Code = Extract<AutomationResult, { success: false }>['error']['code'];
const messages: Record<Code, string> = {
  INVALID_INPUT: '规则请求无效',
  AUTH_REQUIRED: '请先登录 Hub 工作区',
  ACCOUNT_CHANGED: '账号已变化，请重新打开',
  FORBIDDEN: '当前账号无权访问',
  UNAVAILABLE: '主动运行策略暂时不可用，请刷新',
  CONFLICT: '规则已变化，请刷新核对',
  OUTCOME_UNKNOWN: '保存结果未确认，请刷新核对后再操作',
};
const failed = (code: Code): AutomationResult => ({
  success: false,
  error: { code, message: messages[code] },
});
export class HubAutomationService {
  constructor(private readonly ports: AutomationPorts) {}
  private current(identity: WorkCredential) {
    const now = this.ports.credential();
    return (
      !!now &&
      now.scope === identity.scope &&
      now.epoch === identity.epoch &&
      now.origin === identity.origin &&
      now.token === identity.token
    );
  }
  async execute(raw: AutomationInput): Promise<AutomationResult> {
    const input = AutomationInputSchema.safeParse(raw);
    if (!input.success) return failed('INVALID_INPUT');
    const identity = this.ports.credential();
    if (!identity) return failed('AUTH_REQUIRED');
    const write = input.data.action !== 'snapshot';
    const { groupId, ...command } = input.data;
    try {
      const response = await this.ports.request(
        identity,
        '/api/bot-groups/' + groupId + '/automation',
        write ? command : undefined,
      );
      if (!this.current(identity)) return failed(write ? 'OUTCOME_UNKNOWN' : 'ACCOUNT_CHANGED');
      if (response.status === 401 || response.status === 403) return failed('FORBIDDEN');
      if (write && response.status === 409) return failed('CONFLICT');
      if (response.status !== 200)
        return failed(write && response.status >= 500 ? 'OUTCOME_UNKNOWN' : 'UNAVAILABLE');
      const result = z
        .object({ ok: z.literal(true) })
        .and(AutomationViewSchema)
        .safeParse(response.data);
      if (!result.success || result.data.groupId !== groupId)
        return failed(write ? 'OUTCOME_UNKNOWN' : 'UNAVAILABLE');
      if (
        input.data.action !== 'snapshot' &&
        result.data.policy.revision !== input.data.expectedRevision + 1
      )
        return failed('OUTCOME_UNKNOWN');
      return { success: true, data: AutomationViewSchema.parse(result.data) };
    } catch {
      return failed(write ? 'OUTCOME_UNKNOWN' : 'UNAVAILABLE');
    }
  }
}
