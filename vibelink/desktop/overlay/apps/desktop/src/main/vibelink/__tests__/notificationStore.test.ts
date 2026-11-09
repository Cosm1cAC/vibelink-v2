import { expect, it } from 'vitest';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { HubNotificationStore } from '../notificationStore';
import { emptyNotificationDisk } from '../notificationService';
it('retains a nonsecret disabled tombstone across a locked encrypted store and restart', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'vibelink-notifications-off-'));
  try {
    const safe = {
      isEncryptionAvailable: () => true,
      encryptString: (v: string) => Buffer.from(v.split('').reverse().join('')),
      decryptString: (v: Buffer) => v.toString().split('').reverse().join(''),
    };
    const store = new HubNotificationStore(directory, safe);
    const disk = emptyNotificationDisk();
    disk.settings.enabled = true;
    store.save('A', disk);
    store.disable('A');
    expect(() =>
      new HubNotificationStore(directory, { ...safe, isEncryptionAvailable: () => false }).save(
        'A',
        { ...disk, settings: { enabled: false, preview: false } },
      ),
    ).toThrow();
    expect(new HubNotificationStore(directory, safe).load('A').settings.enabled).toBe(false);
    expect(
      fs.readFileSync(
        path.join(
          directory,
          fs.readdirSync(directory).find((n) => n.endsWith('.disabled'))!,
        ),
        'utf8',
      ),
    ).toBe('1');
    store.save('A', disk);
    expect(store.load('A').settings.enabled).toBe(true);
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});
it('keeps account settings encrypted and refuses corrupt or unavailable secure storage', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'vibelink-notifications-'));
  try {
    const safe = {
      isEncryptionAvailable: () => true,
      encryptString: (value: string) => Buffer.from(value.split('').reverse().join('')),
      decryptString: (value: Buffer) => value.toString().split('').reverse().join(''),
    };
    const store = new HubNotificationStore(directory, safe);
    const disk = emptyNotificationDisk();
    disk.settings.enabled = true;
    store.save('A', disk);
    expect(new HubNotificationStore(directory, safe).load('A').settings.enabled).toBe(true);
    expect(store.load('B').settings.enabled).toBe(false);
    const file = path.join(directory, fs.readdirSync(directory)[0]);
    expect(fs.readFileSync(file, 'utf8')).not.toContain('settings');
    fs.writeFileSync(file, 'corrupt');
    expect(() => store.load('A')).toThrow();
    expect(() =>
      new HubNotificationStore(directory, { ...safe, isEncryptionAvailable: () => false }).save(
        'B',
        disk,
      ),
    ).toThrow();
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});
