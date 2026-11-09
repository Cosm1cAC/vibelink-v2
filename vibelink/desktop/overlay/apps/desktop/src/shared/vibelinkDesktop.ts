export type VibeLinkRequestKind = 'state' | 'action';
export interface VibeLinkReply { ok: boolean; status: number; data: unknown }
export interface VibeLinkDesktopBridge {
  readonly enabled: boolean;
  auth(input: import('./vibelinkAuth').HubAuthInput): Promise<import('./vibelinkAuth').HubAuthResult>;
  work(input: import('./vibelinkWork').HubWorkInput): Promise<import('./vibelinkWork').HubWorkResult>;
  notifications(input: import('./vibelinkNotifications').NotificationInput): Promise<import('./vibelinkNotifications').NotificationResult>;
  onNotificationOpen(listener:(target:import('./vibelinkNotifications').NotificationTarget)=>void):()=>void;
  onAuthState(listener: (result: import('./vibelinkAuth').HubAuthResult) => void): () => void;
  request(kind: VibeLinkRequestKind, payload?: Record<string, unknown>): Promise<VibeLinkReply>;
}
