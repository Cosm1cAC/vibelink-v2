import { afterEach, expect, test, vi } from 'vitest';
import { requestVibeLink } from './desktopBridge';
afterEach(() => vi.unstubAllGlobals());
test('native desktop uses the narrow main-process bridge rather than file-origin fetch', async () => {
  const request = vi.fn().mockResolvedValue({ ok: true, status: 200, data: { hubOk: true } });
  const fetch = vi.fn().mockRejectedValue(new Error('file origin cannot reach Hub'));
  vi.stubGlobal('window', { electronAPI: { vibelink: { request } } });
  vi.stubGlobal('fetch', fetch);
  expect(await requestVibeLink('state')).toEqual({ hubOk: true });
  expect(request).toHaveBeenCalledWith('state', undefined);
  expect(fetch).not.toHaveBeenCalled();
});
