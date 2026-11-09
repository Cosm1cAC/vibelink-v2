import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { z } from 'zod';
import {
  NotificationEntrySchema,
  NotificationSettingsSchema,
} from '../../shared/vibelinkNotifications.js';
import type { HubSafeStorage } from './authVault.js';
import { emptyNotificationDisk, type NotificationDisk } from './notificationService.js';

const DiskSchema = z.object({
  settings: NotificationSettingsSchema,
  baselined: z.boolean(),
  seen: z.record(z.string(), z.string()),
  entries: z.array(NotificationEntrySchema).max(500),
  error: z.string(),
});

/** Main-only, OS-encrypted notification state. No plaintext fallback. */
export class HubNotificationStore {
  constructor(
    private readonly directory: string,
    private readonly safe: HubSafeStorage,
  ) {}
  private file(scope: string): string {
    return path.join(this.directory, `${createHash('sha256').update(scope).digest('hex')}.enc`);
  }
  private ensure(): void {
    if (
      !this.safe.isEncryptionAvailable() ||
      this.safe.getSelectedStorageBackend?.() === 'basic_text'
    )
      throw new Error('Secure storage unavailable');
  }
  load(scope: string): NotificationDisk {
    const file = this.file(scope);
    if (!fs.existsSync(file)) return emptyNotificationDisk();
    this.ensure();
    if (fs.statSync(file).size > 8 * 1024 * 1024) throw new Error('Journal exceeds limit');
    const disk = DiskSchema.parse(JSON.parse(this.safe.decryptString(fs.readFileSync(file))));
    if (fs.existsSync(`${file}.disabled`)) disk.settings.enabled = false;
    return disk;
  }
  disable(scope: string): void {
    fs.mkdirSync(this.directory, { recursive: true });
    const descriptor = fs.openSync(`${this.file(scope)}.disabled`, 'w', 0o600);
    try {
      fs.writeFileSync(descriptor, '1');
      fs.fsyncSync(descriptor);
    } finally {
      fs.closeSync(descriptor);
    }
  }
  save(scope: string, disk: NotificationDisk): void {
    this.ensure();
    const value = JSON.stringify(DiskSchema.parse(disk));
    if (Buffer.byteLength(value) > 8 * 1024 * 1024) throw new Error('Journal exceeds limit');
    const encrypted = this.safe.encryptString(value);
    fs.mkdirSync(this.directory, { recursive: true });
    const file = this.file(scope),
      temporary = `${file}.${process.pid}.tmp`;
    try {
      const descriptor = fs.openSync(temporary, 'w', 0o600);
      try {
        fs.writeFileSync(descriptor, encrypted);
        fs.fsyncSync(descriptor);
      } finally {
        fs.closeSync(descriptor);
      }
      fs.renameSync(temporary, file);
      if (disk.settings.enabled) fs.rmSync(`${file}.disabled`, { force: true });
    } finally {
      if (fs.existsSync(temporary)) fs.unlinkSync(temporary);
    }
  }
}
