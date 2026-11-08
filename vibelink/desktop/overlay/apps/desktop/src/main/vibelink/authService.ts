import crypto from 'node:crypto';
import { HubAuthInputSchema, HubSessionSchema, type HubAuthError, type HubAuthSnapshot, type HubSession, type HubAuthInput, type HubAuthResult, type HubAuthSpec } from '../../shared/vibelinkAuth.js';

export interface HubCredential { origin: string; token: string; session: HubSession }
export interface HubResponse { status: number; data: unknown }
export interface HubRequest { token?: string; body?: Record<string, unknown> }

export interface HubAuthPorts {
  readCredential(): Promise<HubCredential | null>;
  request(origin: string, path: string, request: HubRequest): Promise<HubResponse>;
  readLocalAdmin(): Promise<string | null>;
  commitOwner(owner: string | null): Promise<void>;
  ensureVault?(): Promise<void>;
  writeCredential?(credential: HubCredential): Promise<void>;
  trustLocalOwner?(session: HubSession): Promise<void>;
  isTrustedLocalOwner?(session: HubSession): Promise<boolean>;
  clearCredential?(): Promise<void>;
  revokeCapability?(): void;
  onStateChanged?(): void;
}

export function hubDataOwnerId(session: HubSession): string {
  return `hub_${crypto.createHash('sha256').update(JSON.stringify([session.issuer, session.edition, session.userId])).digest('hex').slice(0, 40)}`;
}

/** Main-owned Hub identity. Credentials are deliberately absent from its projection. */
export class HubAuthService implements HubAuthSpec {
  private snapshot: HubAuthSnapshot = { status: 'signed_out', session: null, qr: null };
  private qr: { id: string; pollSecret: string; origin: string; edition: 'dev' | 'beta' } | null = null;
  private epoch = 0;
  private credential: HubCredential | null = null;
  private commitTail: Promise<void> = Promise.resolve();
  private expiry: Promise<HubAuthResult> | null = null;
  constructor(private readonly ports: HubAuthPorts) {}

  private serialized<T>(operation: () => Promise<T>): Promise<T> {
    const result = this.commitTail.then(operation);
    this.commitTail = result.then(() => undefined, () => undefined);
    return result;
  }

  private current(): HubAuthResult { return { success: true, data: this.getSnapshot() }; }

  private async commitCredential(credential: HubCredential, epoch: number, persist: boolean, local = false): Promise<HubAuthResult> {
    return this.serialized(async (): Promise<HubAuthResult> => {
      if (epoch !== this.epoch) return this.current();
      if (credential.session.expiresAt <= Date.now()) return { success: false, error: { code: 'AUTH_EXPIRED', message: '登录已过期，请重新登录', recoverable: true } };
      if (persist) await this.ports.writeCredential!(credential);
      // A logout/new login may supersede an in-progress OS vault operation.
      // Keep cleanup in this same queue so it cannot erase a newer account.
      if (epoch !== this.epoch) {
        if (persist) await this.ports.clearCredential?.();
        return this.current();
      }
      if (local) await this.ports.trustLocalOwner!(credential.session);
      const localOwner = local || await this.ports.isTrustedLocalOwner?.(credential.session);
      if (epoch !== this.epoch) {
        if (persist) await this.ports.clearCredential?.();
        return this.current();
      }
      await this.ports.commitOwner(localOwner ? 'local-v1' : hubDataOwnerId(credential.session));
      if (epoch !== this.epoch) {
        if (persist) await this.ports.clearCredential?.();
        await this.ports.commitOwner(null);
        return this.current();
      }
      this.credential = credential;
      this.snapshot = { status: 'authenticated', session: credential.session, qr: null };
      return this.current();
    });
  }

  private async request(origin: string, path: string, request: HubRequest): Promise<HubResponse> {
    try { return await this.ports.request(origin, path, request); }
    catch { throw Object.assign(new Error('Hub unreachable'), { hubAuthCode: 'NETWORK_UNAVAILABLE' }); }
  }

  private failure(error: unknown, fallback: HubAuthError['code']): HubAuthResult {
    const code = error && typeof error === 'object' && 'hubAuthCode' in error
      ? (error as { hubAuthCode: HubAuthError['code'] }).hubAuthCode : fallback;
    return { success: false, error: { code, recoverable: true, message: code === 'NETWORK_UNAVAILABLE'
      ? '暂时无法连接 VibeLink Hub，请检查连接后重试' : code === 'CREDENTIAL_STORE_UNAVAILABLE'
        ? '无法使用本机安全凭据存储，请解锁系统后重试' : '登录暂时未完成，请重试' } };
  }

  getSnapshot(): HubAuthSnapshot { return structuredClone(this.snapshot); }
  hasPendingQr(): boolean { return this.qr !== null; }
  /** Main-only capability; never serialize through IPC. */
  getCredential(): HubCredential | null {
    if (this.credential && this.credential.session.expiresAt <= Date.now()) void this.expireIfNeeded();
    return this.snapshot.status === 'authenticated' ? this.credential : null;
  }

  private async acknowledge(qr: { id: string; pollSecret: string; origin: string }, epoch: number): Promise<HubAuthResult> {
    const response = await this.request(qr.origin, '/api/auth/qr/ack', { body: { id: qr.id, pollSecret: qr.pollSecret } });
    if (epoch !== this.epoch) return this.current();
    if (response.status !== 200) return this.failure(null, 'NETWORK_UNAVAILABLE');
    const status = (response.data as { status?: string })?.status;
    if (status === 'consumed') {
      this.qr = null;
      return this.current();
    }
    if (status === 'denied' || status === 'expired') {
      ++this.epoch;
      this.qr = null; this.credential = null;
      this.snapshot = { status: 'signed_out', session: null, qr: null };
      this.ports.revokeCapability?.();
      await this.serialized(async () => {
        await this.ports.clearCredential?.();
        await this.ports.commitOwner(null);
      });
      return this.current();
    }
    // Keep the private proof for retry; only an explicit consumed is final.
    return this.failure(null, 'LOGIN_FAILED');
  }

  async expireIfNeeded(): Promise<HubAuthResult> {
    if (this.expiry) return this.expiry;
    if (!this.credential || this.credential.session.expiresAt > Date.now()) return this.current();
    ++this.epoch;
    this.qr = null; this.credential = null;
    this.snapshot = { status: 'signed_out', session: null, qr: null };
    this.ports.revokeCapability?.();
    this.ports.onStateChanged?.();
    this.expiry = this.serialized(async () => {
      try {
        await this.ports.clearCredential?.();
        await this.ports.commitOwner(null);
        return this.current();
      } catch (error) { return this.failure(error, 'LOGIN_FAILED'); }
      finally { this.ports.onStateChanged?.(); }
    }).finally(() => { this.expiry = null; });
    return this.expiry;
  }

  async pollQr(): Promise<HubAuthResult> {
    const qr = this.qr;
    const epoch = this.epoch;
    if (!qr) return { success: true, data: this.snapshot };
    try {
      const body = { id: qr.id, pollSecret: qr.pollSecret };
      if (this.snapshot.status === 'authenticated') {
        return await this.acknowledge(qr, epoch);
      }
      const response = await this.request(qr.origin, '/api/auth/qr/poll', { body });
      if (epoch !== this.epoch) return { success: true, data: this.snapshot };
      const data = response.data as { status: string; token?: string; session?: unknown };
      if (response.status !== 200) return { success: false, error: { code: 'NETWORK_UNAVAILABLE', message: '暂时无法查询扫码状态', recoverable: true } };
      if (data.status === 'approved') {
        const session = HubSessionSchema.parse(data.session);
        if (!data.token || session.edition !== qr.edition) throw new Error('invalid approval');
        await this.commitCredential({ origin: qr.origin, token: data.token, session }, epoch, true);
        if (epoch !== this.epoch) return this.current();
        return await this.acknowledge(qr, epoch);
      } else if (this.snapshot.qr && ['pending', 'scanned', 'denied', 'expired'].includes(data.status)) {
        this.snapshot = { ...this.snapshot, qr: { ...this.snapshot.qr, status: data.status as 'pending' | 'scanned' | 'denied' | 'expired' } };
        if (data.status === 'denied' || data.status === 'expired') this.qr = null;
      }
      return { success: true, data: this.snapshot };
    } catch (error) { return epoch === this.epoch ? this.failure(error, 'LOGIN_FAILED') : this.current(); }
  }

  async execute(input: HubAuthInput): Promise<HubAuthResult> {
    let epoch = this.epoch;
    try {
      if (!HubAuthInputSchema.safeParse(input).success) return { success: false, error: { code: 'INVALID_INPUT', message: '登录请求无效', recoverable: true } };
      if (input.action === 'snapshot') return { success: true, data: this.getSnapshot() };
      if (this.snapshot.status === 'authenticated' && input.action !== 'logout') {
        if (input.action === 'initialize') return this.current();
        return { success: false, error: { code: 'LOGIN_FAILED', message: '请先退出当前账号，再选择其他登录方式', recoverable: true } };
      }
      epoch = ++this.epoch;
      if (input.action === 'logout') {
        const credential = this.credential;
        this.qr = null; this.credential = null;
        this.snapshot = { status: 'signed_out', session: null, qr: null };
        this.ports.revokeCapability?.();
        await this.serialized(async () => {
          await this.ports.clearCredential?.();
          await this.ports.commitOwner(null);
        });
        if (credential) {
          try { await this.request(credential.origin, '/api/auth/logout', { token: credential.token, body: {} }); } catch { /* Local logout remains available offline. */ }
        }
        return this.current();
      }
      if (input.action === 'cancelQr') {
        this.qr = null;
        if (this.snapshot.status !== 'authenticated') this.snapshot = { status: 'signed_out', session: null, qr: null };
        await this.commitTail;
        return this.current();
      }
      if (input.action === 'startQr') {
        await this.ports.ensureVault!();
        const origin = input.edition === 'beta' ? 'https://hub.vibelink.cloud' : 'http://127.0.0.1:18995';
        const response = await this.request(origin, '/api/auth/qr/start', { body: { deviceName: 'VibeLink Desktop' } });
        if (epoch !== this.epoch) return { success: true, data: this.snapshot };
        const data = response.data as { id: string; pollSecret: string; link: string; verificationCode: string; expiresAt: number; purpose: string };
        if (response.status !== 200 || data.purpose !== 'desktop_login' || !data.id || !data.pollSecret || !data.link.startsWith('vibelink://login?')) throw new Error('invalid QR');
        this.qr = { id: data.id, pollSecret: data.pollSecret, origin, edition: input.edition };
        this.snapshot = { status: 'signed_out', session: null, qr: { link: data.link, verificationCode: data.verificationCode, expiresAt: data.expiresAt, status: 'pending', edition: input.edition } };
        return { success: true, data: this.snapshot };
      }
      if (input.action === 'activateBeta' || input.action === 'useLocalWorkspace') {
        await this.ports.ensureVault!();
        const local = input.action === 'useLocalWorkspace';
        const origin = local ? 'http://127.0.0.1:18995' : 'https://hub.vibelink.cloud';
        const admin = local ? await this.ports.readLocalAdmin() : undefined;
        if (local && !admin) return { success: false, error: { code: 'LOGIN_FAILED', message: '未找到本机工作区，请先启动本机 Hub', recoverable: true } };
        const response = await this.request(origin, local ? '/api/auth/session' : '/api/auth/beta', local
          ? { token: admin!, body: { deviceName: 'VibeLink Desktop' } }
          : { body: { code: input.code.trim(), deviceName: 'VibeLink Desktop' } });
        if (epoch !== this.epoch) return { success: true, data: this.snapshot };
        if (response.status !== 200) return { success: false, error: { code: 'LOGIN_FAILED', message: '内测码无效或暂时无法登录', recoverable: true } };
        const body = response.data as { session: unknown; token: string };
        const session = HubSessionSchema.parse(body.session);
        if (session.edition !== (local ? 'dev' : 'beta') || (local && (session.userId !== 'owner' || session.tenantId !== null)) || !body.token) return { success: false, error: { code: 'LOGIN_FAILED', message: '登录响应无效', recoverable: true } };
        return await this.commitCredential({ origin, token: body.token, session }, epoch, true, local);
      }
      const credential = await this.ports.readCredential();
      if (credential) {
        const response = await this.request(credential.origin, '/api/auth/me', { token: credential.token });
        if (epoch !== this.epoch) return { success: true, data: this.snapshot };
        if (response.status === 401) await this.serialized(async () => { if (epoch === this.epoch) await this.ports.clearCredential?.(); });
        if (epoch !== this.epoch) return this.current();
        if (response.status !== 200) return { success: false, error: { code: response.status === 401 ? 'AUTH_EXPIRED' : 'NETWORK_UNAVAILABLE', message: '登录已失效或暂时无法验证，请重新登录', recoverable: true } };
        const session = HubSessionSchema.parse((response.data as { session: unknown }).session);
        if (session.expiresAt <= Date.now() || hubDataOwnerId(session) !== hubDataOwnerId(credential.session)) {
          await this.serialized(async () => { if (epoch === this.epoch) await this.ports.clearCredential?.(); });
          if (epoch !== this.epoch) return this.current();
          return { success: false, error: { code: 'AUTH_EXPIRED', message: '登录身份已失效，请重新登录', recoverable: true } };
        }
        return await this.commitCredential({ ...credential, session }, epoch, false);
      }
      return { success: true, data: { status: 'signed_out', session: null, qr: null } };
    } catch (error) { return epoch === this.epoch ? this.failure(error, 'CREDENTIAL_STORE_UNAVAILABLE') : this.current(); }
  }
}
