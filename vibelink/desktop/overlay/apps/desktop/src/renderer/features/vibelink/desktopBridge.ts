import type { VibeLinkRequestKind } from '../../../shared/vibelinkDesktop';

/** Native file:// windows always use the scoped preload bridge. Browser fixtures
 * exercise the same UI with a substituted HTTP boundary and contain no tokens. */
export async function requestVibeLink<T>(kind: VibeLinkRequestKind, payload?: Record<string, unknown>): Promise<T> {
  const native = window.electronAPI?.vibelink;
  const reply = native
    ? await native.request(kind, payload)
    : await fetch(`/api/vibelink/${kind}`, {
        method: kind === 'state' ? 'GET' : 'POST', cache: 'no-store',
        ...(payload ? { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) } : {}),
      }).then(async response => ({ ok: response.ok, status: response.status, data: await response.json() }));
  if (!reply.ok) {
    const error = reply.data && typeof reply.data === 'object' && 'error' in reply.data ? String(reply.data.error) : `HTTP ${reply.status}`;
    throw new Error(error);
  }
  return reply.data as T;
}
