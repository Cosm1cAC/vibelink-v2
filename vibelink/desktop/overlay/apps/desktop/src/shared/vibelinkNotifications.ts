import { z } from 'zod';
const Id = z
  .string()
  .min(1)
  .max(200)
  .regex(/^[a-zA-Z0-9_-]+$/);
export const NotificationSettingsSchema = z.object({ enabled: z.boolean(), preview: z.boolean() });
export const NotificationReceiptSchema = z.object({
  type: z.enum(['show', 'click', 'close', 'error']),
  at: z.number(),
  attempt: z.number().optional(),
});
export const NotificationEntrySchema = z.object({
  id: z.string().min(1),
  key: z.string(),
  groupId: Id,
  workId: Id,
  eventStatus: z.enum(['completed', 'needs_input', 'failed']),
  updatedAt: z.number(),
  createdAt: z.number(),
  state: z.enum(['pending', 'dispatching', 'delivered', 'failed', 'unknown', 'suppressed']),
  attempt: z.number().int().nonnegative(),
  attemptedAt: z.number().default(0),
  error: z.string(),
  receipts: z.array(NotificationReceiptSchema),
  preview: z.object({ title: z.string(), body: z.string() }).nullable(),
});
export type NotificationEntry = z.infer<typeof NotificationEntrySchema>;
export const NotificationSnapshotSchema = z.object({
  scope: z.string(),
  settings: NotificationSettingsSchema,
  baselined: z.boolean(),
  entries: z.array(NotificationEntrySchema),
  error: z.string(),
  capability: z.object({ supported: z.boolean(), registered: z.boolean() }).optional(),
});
export type NotificationSnapshot = z.infer<typeof NotificationSnapshotSchema>;
export const NotificationInputSchema = z.discriminatedUnion('action', [
  z.object({ action: z.literal('snapshot') }).strict(),
  z.object({ action: z.literal('configure'), enabled: z.boolean(), preview: z.boolean() }).strict(),
  z.object({ action: z.literal('resend'), id: z.string().min(1).max(300) }).strict(),
]);
export type NotificationInput = z.infer<typeof NotificationInputSchema>;
export const NotificationResultSchema = z.discriminatedUnion('success', [
  z.object({ success: z.literal(true), data: NotificationSnapshotSchema }),
  z.object({
    success: z.literal(false),
    error: z.object({
      code: z.enum([
        'INVALID_INPUT',
        'AUTH_REQUIRED',
        'ACCOUNT_CHANGED',
        'STORAGE_UNAVAILABLE',
        'NETWORK_UNAVAILABLE',
        'NOT_ENABLED',
        'NOT_FOUND',
      ]),
      message: z.string(),
      recoverable: z.boolean(),
    }),
  }),
]);
export type NotificationResult = z.infer<typeof NotificationResultSchema>;
export interface NotificationSpec {
  execute(input: NotificationInput): Promise<NotificationResult>;
}
export const NotificationTargetSchema = z.object({
  issuer: z.string(),
  edition: z.enum(['dev', 'beta']),
  userId: z.string(),
  groupId: Id,
  workId: Id,
});
export type NotificationTarget = z.infer<typeof NotificationTargetSchema>;
/** Future channels are ports only. This edition supplies solely an OS adapter. */
export type NotificationChannel = 'os' | 'im' | 'email';
