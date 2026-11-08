import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { z } from 'zod';
import { OutboxRowSchema } from '../../shared/vibelinkWork.js';
import type { HubSafeStorage } from './authVault.js';
import type { WorkDisk } from './workService.js';

const DiskSchema = z.object({
  entries: z.array(OutboxRowSchema).max(100),
  read: z.record(z.string(), z.number().finite().nonnegative()),
});

/** Private task inputs are encrypted by the same OS-backed boundary as login. */
export class HubWorkStore {
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
  load(scope: string): WorkDisk {
    const file = this.file(scope);
    if (!fs.existsSync(file)) return { entries: [], read: {} };
    this.ensure();
    if (fs.statSync(file).size > 8 * 1024 * 1024) throw new Error('Journal exceeds limit');
    return DiskSchema.parse(JSON.parse(this.safe.decryptString(fs.readFileSync(file))));
  }
  save(scope: string, disk: WorkDisk): void {
    this.ensure();
    const value = JSON.stringify(DiskSchema.parse(disk));
    if (Buffer.byteLength(value) > 8 * 1024 * 1024) throw new Error('Journal exceeds limit');
    const encrypted = this.safe.encryptString(value);
    fs.mkdirSync(this.directory, { recursive: true });
    const file = this.file(scope);
    const temporary = `${file}.${process.pid}.tmp`;
    try {
      const descriptor = fs.openSync(temporary, 'w', 0o600);
      try {
        fs.writeFileSync(descriptor, encrypted);
        fs.fsyncSync(descriptor);
      } finally {
        fs.closeSync(descriptor);
      }
      fs.renameSync(temporary, file);
    } finally {
      if (fs.existsSync(temporary)) fs.unlinkSync(temporary);
    }
  }
}
