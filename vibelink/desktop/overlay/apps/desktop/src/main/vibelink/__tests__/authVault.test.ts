import { expect, it, vi } from 'vitest';
// Production HubAuthVault protects empty-profile Keychain behavior, ciphertext
// and durable logout while locked. Existing Cindy vault tests cover different
// storage keys; these unit tests use only fake encryption and OS temp files.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { HubAuthVault } from '../authVault';

it('does not probe the OS credential store for an empty profile and refuses plaintext persistence', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'vibelink-auth-vault-test-'));
  try {
    const safe = { isEncryptionAvailable: vi.fn(() => false), encryptString: vi.fn(), decryptString: vi.fn() };
    const vault = new HubAuthVault(path.join(dir, 'hub-session.enc'), safe);
    expect(await vault.readCredential()).toBeNull();
    expect(safe.isEncryptionAvailable).not.toHaveBeenCalled();
    await expect(vault.ensureAvailable()).rejects.toThrow('CREDENTIAL_STORE_UNAVAILABLE');
    expect(fs.readdirSync(dir)).toEqual([]);
    expect(safe.encryptString).not.toHaveBeenCalled();
  } finally { fs.rmSync(dir, { recursive: true, force: true }); }
});

it('can persist logout while the OS vault is locked, without deleting an existing ciphertext', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'vibelink-auth-vault-test-'));
  try {
    const file = path.join(dir, 'hub-session.enc');
    fs.writeFileSync(file, Buffer.from('fake-locked-ciphertext'));
    const safe = { isEncryptionAvailable: vi.fn(() => false), encryptString: vi.fn(), decryptString: vi.fn() };
    const vault = new HubAuthVault(file, safe);
    await vault.clearCredential();
    expect(await new HubAuthVault(file, safe).readCredential()).toBeNull();
    expect(fs.readFileSync(file, 'utf8')).toBe('fake-locked-ciphertext');
  } finally { fs.rmSync(dir, { recursive: true, force: true }); }
});

it('refuses an encryption backend that reports availability but stores basic plaintext', async () => {
  const vault = new HubAuthVault('/unused/fake.enc', { isEncryptionAvailable: () => true, getSelectedStorageBackend: () => 'basic_text', encryptString: vi.fn(), decryptString: vi.fn() });
  await expect(vault.ensureAvailable()).rejects.toThrow('CREDENTIAL_STORE_UNAVAILABLE');
});

it('round-trips encrypted credentials and retains only an explicitly trusted local principal on logout', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'vibelink-auth-vault-test-'));
  try {
    const safe = {
      isEncryptionAvailable: () => true,
      encryptString: (text: string) => Buffer.from(Buffer.from(text).map((byte) => byte ^ 0xa5)),
      decryptString: (cipher: Buffer) => Buffer.from(cipher).map((byte) => byte ^ 0xa5).toString(),
    };
    const file = path.join(dir, 'hub-session.enc');
    const vault = new HubAuthVault(file, safe);
    const session = { userId: 'owner', label: '本机', tenantId: null, edition: 'dev' as const, sessionId: 's_fake', expiresAt: 9999999999999, issuer: `hub_${'a'.repeat(24)}`, hubId: `hub_${'a'.repeat(24)}`, permissions: ['client'] as ['client'] };
    const credential = { origin: 'http://127.0.0.1:18995', token: 'fake-vault-token', session };
    await vault.writeCredential(credential);
    await vault.trustLocalOwner(session);
    expect(fs.readFileSync(file, 'utf8')).not.toContain('fake-vault-token');
    const reloaded = new HubAuthVault(file, safe);
    expect(await reloaded.readCredential()).toEqual(credential);
    expect(await reloaded.isTrustedLocalOwner(session)).toBe(true);
    expect(await reloaded.isTrustedLocalOwner({ ...session, issuer: `hub_${'b'.repeat(24)}`, hubId: `hub_${'b'.repeat(24)}` })).toBe(false);
    await reloaded.clearCredential();
    expect(await new HubAuthVault(file, safe).readCredential()).toBeNull();
    expect(await reloaded.isTrustedLocalOwner(session)).toBe(true);
  } finally { fs.rmSync(dir, { recursive: true, force: true }); }
});
