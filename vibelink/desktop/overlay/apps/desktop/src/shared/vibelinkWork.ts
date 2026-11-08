import { z } from 'zod';
const Id = z
  .string()
  .min(1)
  .max(200)
  .regex(/^[a-zA-Z0-9_-]+$/);
export const HubMessageSchema = z.object({
  id: z.string(),
  senderId: z.string(),
  senderName: z.string(),
  text: z.string(),
  at: z.number(),
  status: z.string().default(''),
  linkedSessionId: z.string().default(''),
});
export const WorkMandateSchema = z.object({
  authority: z.string(),
  revokedAt: z.number(),
  acceptance: z.string(),
  members: z.array(
    z.object({
      botId: z.string(),
      agent: z.string(),
      model: z.string(),
      device: z.string(),
      configuredWorkspace: z.string(),
      allowWrite: z.boolean(),
    }),
  ),
});
export const WorkRowSchema = z.object({
  id: Id,
  groupId: Id,
  title: z.string(),
  text: z.string().default(''),
  status: z.string(),
  phase: z.string().default(''),
  createdAt: z.number(),
  updatedAt: z.number(),
  error: z.string().default(''),
  result: z.string().default(''),
  canManage: z.boolean().default(false),
  retryable: z.boolean().default(false),
  archived: z.boolean().default(false),
  acceptance: z.string().default(''),
  billing: z.unknown().optional(),
  mandate: WorkMandateSchema.optional(),
  budget: z.object({ tokens: z.number(), requests: z.number() }).optional(),
  unread: z.boolean().optional(),
  inputs: z
    .array(z.object({ id: z.string(), text: z.string(), createdAt: z.number() }))
    .default([]),
  steps: z
    .array(
      z.object({
        id: z.string(),
        botId: z.string(),
        kind: z.string(),
        status: z.string(),
        sessionId: z.string().default(''),
        output: z.string().default(''),
        error: z.string().default(''),
      }),
    )
    .default([]),
});
export type HubWork = z.infer<typeof WorkRowSchema>;
export const HubGroupSchema = z.object({
  id: Id,
  name: z.string(),
  description: z.string().default(''),
  botIds: z.array(z.string()).default([]),
});
export const HubBotSchema = z.object({
  id: Id,
  name: z.string(),
  jobTitle: z.string().default(''),
  archived: z.boolean().default(false),
});
export const OutboxRowSchema = z.object({
  requestId: z.string(),
  groupId: Id,
  text: z.string(),
  replyToWorkId: z.string().optional(),
  status: z.enum(['pending', 'failed']),
  error: z.string().optional(),
  createdAt: z.number(),
});
export type HubOutbox = z.infer<typeof OutboxRowSchema>;
export const HubWorkInputSchema = z.discriminatedUnion('action', [
  z.object({ action: z.literal('groups') }).strict(),
  z.object({ action: z.literal('messages'), groupId: Id }).strict(),
  z.object({ action: z.literal('bots') }).strict(),
  z
    .object({
      action: z.literal('createGroup'),
      name: z.string().trim().min(1).max(100),
      botIds: z.array(Id).min(1).max(30),
    })
    .strict(),
  z
    .object({
      action: z.literal('list'),
      groupId: Id,
      q: z.string().max(200).optional(),
      archived: z.boolean().optional(),
    })
    .strict(),
  z
    .object({
      action: z.literal('submit'),
      groupId: Id,
      text: z.string().trim().min(1).max(24000),
      replyToWorkId: Id.optional(),
    })
    .strict(),
  z
    .object({
      action: z.literal('manage'),
      groupId: Id,
      workId: Id,
      command: z.enum(['cancel', 'retry', 'archive']),
    })
    .strict(),
  z.object({ action: z.literal('export'), groupId: Id, workId: Id }).strict(),
  z.object({ action: z.literal('outbox') }).strict(),
  z.object({ action: z.literal('dismissFailed'), requestId: Id }).strict(),
  z
    .object({ action: z.literal('markRead'), groupId: Id, workId: Id, updatedAt: z.number() })
    .strict(),
]);
export type HubWorkInput = z.infer<typeof HubWorkInputSchema>;
export const HubWorkDataSchema = z.object({
  kind: z.enum([
    'groups',
    'messages',
    'bots',
    'group',
    'list',
    'accepted',
    'outbox',
    'export',
    'marked',
  ]),
  groups: z.array(HubGroupSchema).optional(),
  messages: z.array(HubMessageSchema).optional(),
  bots: z.array(HubBotSchema).optional(),
  group: HubGroupSchema.optional(),
  work: z.array(WorkRowSchema).optional(),
  outbox: z.array(OutboxRowSchema).optional(),
  requestId: z.string().optional(),
  exported: z.string().optional(),
});
export type HubWorkData = z.infer<typeof HubWorkDataSchema>;
export type HubWorkResult =
  | { success: true; data: HubWorkData }
  | {
      success: false;
      error: {
        code:
          | 'INVALID_INPUT'
          | 'AUTH_REQUIRED'
          | 'ACCOUNT_CHANGED'
          | 'NETWORK_UNAVAILABLE'
          | 'FORBIDDEN'
          | 'HUB_REJECTED'
          | 'STORAGE_UNAVAILABLE';
        message: string;
        recoverable: boolean;
      };
    };
export interface HubWorkSpec {
  execute(input: HubWorkInput): Promise<HubWorkResult>;
}
export const HubWorkErrorSchema = z.object({
  code: z.enum([
    'INVALID_INPUT',
    'AUTH_REQUIRED',
    'ACCOUNT_CHANGED',
    'NETWORK_UNAVAILABLE',
    'FORBIDDEN',
    'HUB_REJECTED',
    'STORAGE_UNAVAILABLE',
  ]),
  message: z.string(),
  recoverable: z.boolean(),
});
export const HubWorkResultSchema = z.discriminatedUnion('success', [
  z.object({ success: z.literal(true), data: HubWorkDataSchema }),
  z.object({ success: z.literal(false), error: HubWorkErrorSchema }),
]);

export type HubMessage = z.infer<typeof HubMessageSchema>;
