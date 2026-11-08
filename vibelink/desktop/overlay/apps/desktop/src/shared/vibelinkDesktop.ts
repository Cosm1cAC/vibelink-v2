export type VibeLinkRequestKind = 'state' | 'action';
export interface VibeLinkReply { ok: boolean; status: number; data: unknown }
export interface VibeLinkDesktopBridge {
  readonly enabled: boolean;
  auth(input: import('./vibelinkAuth').HubAuthInput): Promise<import('./vibelinkAuth').HubAuthResult>;
  onAuthState(listener: (result: import('./vibelinkAuth').HubAuthResult) => void): () => void;
  request(kind: VibeLinkRequestKind, payload?: Record<string, unknown>): Promise<VibeLinkReply>;
}
