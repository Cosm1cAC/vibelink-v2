import { z } from 'zod';

export const HubSessionSchema = z.object({
  userId: z.string().min(1), label: z.string(), tenantId: z.string().nullable(),
  edition: z.enum(['dev', 'beta']), sessionId: z.string().min(1), expiresAt: z.number(),
  issuer: z.string().regex(/^hub_[a-f0-9]{24}$/), hubId: z.string().regex(/^hub_[a-f0-9]{24}$/),
  permissions: z.array(z.literal('client')),
}).refine((session) => session.issuer === session.hubId);
export type HubSession = z.infer<typeof HubSessionSchema>;
export const HubAuthInputSchema = z.discriminatedUnion('action', [
  z.object({ action: z.literal('initialize') }).strict(),
  z.object({ action: z.literal('snapshot') }).strict(),
  z.object({ action: z.literal('activateBeta'), code: z.string().trim().min(1).max(256) }).strict(),
  z.object({ action: z.literal('useLocalWorkspace') }).strict(),
  z.object({ action: z.literal('startQr'), edition: z.enum(['dev', 'beta']) }).strict(),
  z.object({ action: z.literal('cancelQr') }).strict(),
  z.object({ action: z.literal('logout') }).strict(),
]);
export type HubAuthInput = z.infer<typeof HubAuthInputSchema>;
export const HubQrSchema = z.object({
  link: z.string().startsWith('vibelink://login?'), verificationCode: z.string(), expiresAt: z.number(),
  status: z.enum(['pending', 'scanned', 'denied', 'expired']), edition: z.enum(['dev', 'beta']),
});
export const HubAuthSnapshotSchema = z.object({
  status: z.enum(['signed_out', 'authenticated']), session: HubSessionSchema.nullable(), qr: HubQrSchema.nullable(),
});
export type HubAuthSnapshot = z.infer<typeof HubAuthSnapshotSchema>;
export const HubAuthErrorSchema = z.object({
  code: z.enum(['INVALID_INPUT', 'AUTH_EXPIRED', 'NETWORK_UNAVAILABLE', 'CREDENTIAL_STORE_UNAVAILABLE', 'LOGIN_FAILED']),
  message: z.string(), recoverable: z.boolean(),
});
export type HubAuthError = z.infer<typeof HubAuthErrorSchema>;
export type HubAuthResult = { success: true; data: HubAuthSnapshot } | { success: false; error: HubAuthError };
export interface HubAuthSpec { execute(input: HubAuthInput): Promise<HubAuthResult> }
