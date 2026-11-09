import { expect, it } from 'vitest';
import { createOSNotificationAdapter } from '../notificationOS';
it('rejects unbounded OS tag IDs before native construction', () => {
  let created = 0;
  const adapter = createOSNotificationAdapter({
    supported: () => true,
    registered: () => true,
    create: () => {
      created++;
      return { on: () => {}, show: () => {}, close: () => {} };
    },
  });
  expect(() =>
    adapter.create(
      { title: 'VibeLink', body: 'Generic', id: 'a'.repeat(65), groupId: 'b'.repeat(64) } as any,
      { show: () => {}, click: () => {}, close: () => {}, error: () => {} },
    ),
  ).toThrow();
  expect(created).toBe(0);
});
it('requires registration and maps native receipts without displaying during construction', () => {
  let shown = 0;
  const callbacks: Record<string, () => void> = {};
  const receipts: string[] = [];
  const ports = {
    supported: () => true,
    registered: () => true,
    create: (content: any) => {
      expect(content).toEqual({
        title: 'VibeLink',
        body: 'Generic',
        id: 'a'.repeat(64),
        groupId: 'b'.repeat(64),
      });
      return {
        on: (event: string, callback: () => void) => {
          callbacks[event] = callback;
        },
        show: () => {
          shown++;
        },
        close: () => {},
      };
    },
  };
  const adapter = createOSNotificationAdapter(ports);
  const handle = adapter.create(
    { title: 'VibeLink', body: 'Generic', id: 'a'.repeat(64), groupId: 'b'.repeat(64) },
    {
      show: () => receipts.push('show'),
      click: () => receipts.push('click'),
      close: () => receipts.push('close'),
      error: () => receipts.push('error'),
    },
  );
  expect(shown).toBe(0);
  handle.show();
  callbacks.show();
  callbacks.click();
  callbacks.close();
  callbacks.failed();
  expect(shown).toBe(1);
  expect(receipts).toEqual(['show', 'click', 'close', 'error']);
  expect(() =>
    createOSNotificationAdapter({ ...ports, registered: () => false }).create(
      { title: 'VibeLink', body: 'Generic', id: 'a'.repeat(64), groupId: 'b'.repeat(64) },
      { show: () => {}, click: () => {}, close: () => {}, error: () => {} },
    ),
  ).toThrow();
});
