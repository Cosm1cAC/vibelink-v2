import { z } from 'zod';
const Id = z.string().regex(/^[a-zA-Z0-9_-]{1,128}$/);
const Count = z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER);
export const ObserveStatusSchema = z.enum(['completed', 'failed', 'needs_input']);
export const ObserveModeSchema = z.enum(['off', 'observe']);
export const ObserveRuleSchema = z.object({
  id: Id,
  label: z.string().max(120),
  statuses: z.array(ObserveStatusSchema).min(1).max(3),
  version: Count.min(1),
  createdAt: Count,
  revokedAt: Count,
});
export const ObserveDecisionSchema = z.object({
  id: z.string().max(200),
  eventId: z.string().max(200),
  workId: Id,
  workRevision: Count,
  status: z.string().max(80),
  ruleId: Id,
  ruleVersion: Count,
  at: Count,
  outcome: z.enum(['suggested', 'suppressed']),
  reason: z.enum(['matched', 'off', 'revoked']),
});
export const ObserverSchema = z.object({
  coverage: z.literal('latest_work_snapshot'), baselineAt: Count, lastScanAt: Count,
  error: z.enum(['', 'capacity', 'persistence']), decisionLimit: Count, ruleLimit: Count, eventLimit: Count,
});
export const AutomationViewSchema = z.object({
  observer: ObserverSchema,
  groupId: Id,
  policy: z.object({ mode: ObserveModeSchema, revision: Count }),
  rules: z.array(ObserveRuleSchema).max(1000),
  decisions: z.array(ObserveDecisionSchema).max(1000),
});
export type AutomationView = z.infer<typeof AutomationViewSchema>;
export const AutomationInputSchema = z.discriminatedUnion('action', [
  z.object({ action: z.literal('snapshot'), groupId: Id }).strict(),
  z
    .object({
      action: z.literal('setMode'),
      groupId: Id,
      mode: ObserveModeSchema,
      expectedRevision: Count,
    })
    .strict(),
  z
    .object({
      action: z.literal('saveRule'),
      groupId: Id,
      ruleId: Id,
      label: z.string().trim().min(1).max(120),
      statuses: z
        .array(ObserveStatusSchema)
        .min(1)
        .max(3)
        .refine((v) => new Set(v).size === v.length),
      expectedRevision: Count,
    })
    .strict(),
  z
    .object({ action: z.literal('revokeRule'), groupId: Id, ruleId: Id, expectedRevision: Count })
    .strict(),
]);
export type AutomationInput = z.infer<typeof AutomationInputSchema>;
export const AutomationResultSchema = z.discriminatedUnion('success', [
  z.object({ success: z.literal(true), data: AutomationViewSchema }),
  z.object({
    success: z.literal(false),
    error: z.object({
      code: z.enum([
        'INVALID_INPUT',
        'AUTH_REQUIRED',
        'ACCOUNT_CHANGED',
        'FORBIDDEN',
        'UNAVAILABLE',
        'CONFLICT',
        'OUTCOME_UNKNOWN',
      ]),
      message: z.string(),
    }),
  }),
]);
export type AutomationResult = z.infer<typeof AutomationResultSchema>;
export interface AutomationSpec {
  execute(input: AutomationInput): Promise<AutomationResult>;
}
