import { z } from 'zod';

const Count = z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER);
const Id = z.string().regex(/^[A-Za-z0-9_-]{1,128}$/);
export const AdminDaySchema = z.string().regex(/^\d{4}-\d{2}-\d{2}$/).refine((value) => {
  const date = new Date(value + 'T00:00:00Z');
  return !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === value;
}, 'Invalid date');
export const AdminLimitsSchema = z.object({
  dailyRequests: Count, dailyTokens: Count, dailyInputBytes: Count,
  concurrentRequests: Count.min(1).max(16), requestsPerMinute: Count.min(1),
}).strict();
export type AdminLimits = z.infer<typeof AdminLimitsSchema>;
export const AdminSeatSchema = z.object({
  tenantId: Id, label: z.string().max(300), inviteCode: z.string().min(1).max(256),
  activatedAt: Count, lastDevice: z.string().max(300), limits: AdminLimitsSchema, limitsRevision: Count,
});
export type AdminSeat = z.infer<typeof AdminSeatSchema>;
export const AdminUsageSchema = z.object({
  userId: Id, label: z.string().max(300), day: AdminDaySchema, model: z.string().max(300),
  usedRequests: Count, successfulRequests: Count, failedRequests: Count, interruptedRequests: Count,
  inputTokens: Count, outputTokens: Count, cachedTokens: Count, totalTokens: Count,
  estimatedTokens: Count, usageMissingRequests: Count, reservedTokens: Count, chargedTokens: Count,
  inputBytes: Count, limits: AdminLimitsSchema, limitsRevision: Count, warning: z.enum(['', 'near_limit', 'exhausted']),
});
export type AdminUsage = z.infer<typeof AdminUsageSchema>;
export const AdminRequestSchema = z.object({
  requestId: z.string().min(1).max(200), userId: Id, workId: z.string().max(200).default(''), day: AdminDaySchema,
  model: z.string().max(300), protocol: z.string().max(80), startedAt: Count, finishedAt: Count,
  durationMs: Count, outcome: z.string().max(80), httpStatus: Count.max(599).nullable(),
  tokens: z.object({ input: Count, output: Count, cached: Count, total: Count }).nullable(),
  usageMissing: z.boolean(), estimatedTokens: Count,
});
export type AdminRequest = z.infer<typeof AdminRequestSchema>;
export const AdminInputSchema = z.discriminatedUnion('action', [
  z.object({ action: z.literal('capability') }).strict(),
  z.object({ action: z.literal('snapshot'), day: AdminDaySchema }).strict(),
  z.object({ action: z.literal('requests') }).strict(),
  z.object({ action: z.literal('setLimits'), userId: Id, limits: AdminLimitsSchema, expectedRevision: Count }).strict(),
]);
export type AdminInput = z.infer<typeof AdminInputSchema>;
export const AdminDataSchema = z.discriminatedUnion('kind', [
  z.object({ kind: z.literal('capability'), allowed: z.boolean(), reason: z.string() }),
  z.object({ kind: z.literal('snapshot'), day: AdminDaySchema, seats: z.array(AdminSeatSchema).max(1000), users: z.array(AdminUsageSchema).max(2000) }),
  z.object({ kind: z.literal('requests'), requests: z.array(AdminRequestSchema).max(200) }),
  z.object({ kind: z.literal('limitsSaved'), userId: Id, limits: AdminLimitsSchema, limitsRevision: Count }),
]);
export type AdminData = z.infer<typeof AdminDataSchema>;
export const AdminErrorSchema = z.object({
  code: z.enum(['INVALID_INPUT','AUTH_REQUIRED','FORBIDDEN','ACCOUNT_CHANGED','NETWORK_UNAVAILABLE','CREDENTIAL_UNAVAILABLE','HUB_REJECTED','OUTCOME_UNKNOWN','CONFLICT']),
  message: z.string(), recoverable: z.boolean(),
});
export const AdminResultSchema = z.discriminatedUnion('success', [
  z.object({ success: z.literal(true), data: AdminDataSchema }),
  z.object({ success: z.literal(false), error: AdminErrorSchema }),
]);
export type AdminResult = z.infer<typeof AdminResultSchema>;
export interface AdminSpec { execute(input: AdminInput): Promise<AdminResult> }
