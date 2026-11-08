import { expect, it } from 'vitest';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { HubWorkStore } from '../workStore';

it('persists separate encrypted account journals across restart without credentials', () => {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'vibelink-work-store-'));
  try {
    const safe = {
      isEncryptionAvailable: () => true,
      encryptString: (value: string) => Buffer.from(value.split('').reverse().join('')),
      decryptString: (value: Buffer) => value.toString().split('').reverse().join(''),
    };
    const store = new HubWorkStore(temporary, safe);
    store.save('account-A', {
      entries: [
        {
          requestId: 'same-id',
          groupId: 'g1',
          text: 'private task',
          createdAt: 1,
          status: 'pending',
        },
      ],
      read: {},
    });
    expect(new HubWorkStore(temporary, safe).load('account-A').entries[0].requestId).toBe(
      'same-id',
    );
    expect(store.load('account-B').entries).toEqual([]);
    expect(
      fs.readFileSync(path.join(temporary, fs.readdirSync(temporary)[0]), 'utf8'),
    ).not.toContain('private task');
  } finally {
    fs.rmSync(temporary, { recursive: true, force: true });
  }
});
