import { afterEach, expect, it, vi } from 'vitest';
import type { IpcMainInvokeEvent } from 'electron';
import type { PtyManager } from '../ptyManager';
import { createTerminalAccessPolicy } from '../accessPolicy';

afterEach(() => vi.useRealTimers());
it('kills only expired/changed owner terminals and rejects late CLI setup after login switches', () => {
  vi.useFakeTimers();
  let current: { key: string; expiresAt: number } | null = { key: 'owner/one', expiresAt: Date.now() + 1000 };
  let boundary: (force: boolean) => void = () => {};
  const dispose = vi.fn();
  const policy = createTerminalAccessPolicy({ identity: () => current, verifySender: () => {},
    resolveCli: vi.fn(), subscribeInvalidation: callback => { boundary = callback; } });
  policy.attach({ dispose } as unknown as PtyManager);
  const event = {} as IpcMainInvokeEvent;
  const proof = policy.authorize(event);
  policy.created(event, 'cli-1', proof);
  boundary(false); expect(dispose).not.toHaveBeenCalled();
  current = { key: 'owner/two', expiresAt: Date.now() + 1000 };
  boundary(false); expect(dispose).toHaveBeenCalledExactlyOnceWith('cli-1');
  expect(() => policy.authorize(event, 'late-cli', proof)).toThrow(/another login/);
  policy.created(event, 'cli-2', policy.authorize(event));
  vi.advanceTimersByTime(1001); expect(dispose).toHaveBeenCalledWith('cli-2');
  current = null; expect(() => policy.authorize(event)).toThrow();
});
it('rejects untrusted renderer and invalidates terminals at account commit boundaries', () => {
  let trusted = true;
  let boundary: (force: boolean) => void = () => {};
  const dispose = vi.fn();
  const policy = createTerminalAccessPolicy({ identity: () => ({key: 'dev', expiresAt: Date.now() + 10000}),
    verifySender: () => { if (!trusted) throw new Error('untrusted'); }, resolveCli: vi.fn(),
    subscribeInvalidation: callback => { boundary = callback; } });
  policy.attach({ dispose } as unknown as PtyManager);
  const event = {} as IpcMainInvokeEvent;
  policy.created(event, 'cli', policy.authorize(event));
  trusted = false; expect(() => policy.authorize(event, 'cli')).toThrow('untrusted');
  boundary(true); expect(dispose).toHaveBeenCalledWith('cli');
});
