export type VibeLinkRequestKind = 'state' | 'action';
export interface VibeLinkReply { ok: boolean; status: number; data: unknown }
export interface VibeLinkDesktopBridge {
  request(kind: VibeLinkRequestKind, payload?: Record<string, unknown>): Promise<VibeLinkReply>;
}
