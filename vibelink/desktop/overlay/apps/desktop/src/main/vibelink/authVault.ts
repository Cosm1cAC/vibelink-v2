import fs from 'node:fs';
import path from 'node:path';
import { z } from 'zod';
import { HubSessionSchema, type HubSession } from '../../shared/vibelinkAuth.js';
import { hubDataOwnerId, type HubCredential } from './authService.js';

const CredentialSchema = z.object({ origin: z.string().url(), token: z.string().min(1), session: HubSessionSchema });
const VaultSchema = z.object({ version: z.literal(1), credential: CredentialSchema.nullable(), localOwners: z.array(z.string()).default([]) });
type VaultContents = z.infer<typeof VaultSchema>;

export interface HubSafeStorage {
  isEncryptionAvailable(): boolean;
  encryptString(value: string): Buffer;
  decryptString(value: Buffer): string;
  getSelectedStorageBackend?(): string;
}

/** Electron Main-only encrypted persistence. No plaintext or alternate backend. */
export class HubAuthVault {
  constructor(private readonly file: string, private readonly safe: HubSafeStorage) {}

  async ensureAvailable(): Promise<void> {
    if (!this.safe.isEncryptionAvailable() || this.safe.getSelectedStorageBackend?.() === 'basic_text') throw new Error('CREDENTIAL_STORE_UNAVAILABLE');
  }

  private async read(): Promise<VaultContents> {
    if (!fs.existsSync(this.file)) return { version: 1, credential: null, localOwners: [] };
    await this.ensureAvailable();
    return VaultSchema.parse(JSON.parse(this.safe.decryptString(fs.readFileSync(this.file))));
  }

  private async write(value: VaultContents): Promise<void> {
    await this.ensureAvailable();
    const encrypted = this.safe.encryptString(JSON.stringify(VaultSchema.parse(value)));
    fs.mkdirSync(path.dirname(this.file), { recursive: true });
    const temporary = `${this.file}.${process.pid}.tmp`;
    try {
      const descriptor = fs.openSync(temporary, 'w', 0o600);
      try { fs.writeFileSync(descriptor, encrypted); fs.fsyncSync(descriptor); }
      finally { fs.closeSync(descriptor); }
      fs.renameSync(temporary, this.file);
    } finally { if (fs.existsSync(temporary)) fs.unlinkSync(temporary); }
  }

  async readCredential(): Promise<HubCredential | null> {
    if (fs.existsSync(`${this.file}.signed-out`)) return null;
    return (await this.read()).credential;
  }

  async writeCredential(credential: HubCredential): Promise<void> {
    await this.write({ ...await this.read(), credential });
    fs.rmSync(`${this.file}.signed-out`, { force: true });
  }

  async clearCredential(): Promise<void> {
    // A nonsecret tombstone makes logout durable even when Keychain/DPAPI is
    // temporarily unavailable. Existing ciphertext and local-history mappings
    // remain intact; only a new successful encrypted login removes the marker.
    fs.mkdirSync(path.dirname(this.file), { recursive: true });
    const descriptor = fs.openSync(`${this.file}.signed-out`, 'w', 0o600);
    try { fs.writeFileSync(descriptor, '1'); fs.fsyncSync(descriptor); }
    finally { fs.closeSync(descriptor); }
  }

  /** Called only after the explicit localhost admin exchange validates owner. */
  async trustLocalOwner(session: HubSession): Promise<void> {
    if (session.edition !== 'dev' || session.userId !== 'owner' || session.tenantId !== null) throw new Error('INVALID_LOCAL_OWNER');
    const data = await this.read();
    await this.write({ ...data, localOwners: [...new Set([...data.localOwners, hubDataOwnerId(session)])] });
  }

  async isTrustedLocalOwner(session: HubSession): Promise<boolean> {
    return (await this.read()).localOwners.includes(hubDataOwnerId(session));
  }
}
