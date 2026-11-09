import type { HubSession } from '../../../shared/vibelinkAuth';
interface Handoff {
  token: string;
  phase: 'waiting' | 'ready' | 'navigated';
  session: HubSession | null;
  expiresAt: number;
}
let handoff: Handoff | null = null,
  sequence = 0;
const listeners = new Set<() => void>();
function publish(value: Handoff | null): void {
  handoff = value;
  for (const listener of listeners) listener();
}
export const getFirstPhonePairing = () => handoff;
export function subscribeFirstPhonePairing(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}
export function isFirstPhoneOwner(session: HubSession | null): session is HubSession {
  return (
    !!session &&
    session.edition === 'dev' &&
    session.userId === 'owner' &&
    session.tenantId === null &&
    session.expiresAt > Date.now()
  );
}
function same(a: HubSession | null, b: HubSession | null): boolean {
  return (
    isFirstPhoneOwner(a) &&
    isFirstPhoneOwner(b) &&
    a.issuer === b.issuer &&
    a.edition === b.edition &&
    a.userId === b.userId &&
    a.sessionId === b.sessionId
  );
}
export function beginFirstPhonePairing(): string {
  const token = `phone-${Date.now()}-${++sequence}`;
  publish({ token, phase: 'waiting', session: null, expiresAt: Date.now() + 60000 });
  return token;
}
export function cancelFirstPhonePairing(token?: string): void {
  if (!token || handoff?.token === token) publish(null);
}
export function armFirstPhonePairing(token: string, session: HubSession | null): boolean {
  if (
    handoff?.token !== token ||
    handoff.phase !== 'waiting' ||
    handoff.expiresAt <= Date.now() ||
    !isFirstPhoneOwner(session)
  )
    return false;
  publish({ ...handoff, phase: 'ready', session });
  return true;
}
export function markFirstPhoneNavigation(token: string, session: HubSession | null): boolean {
  if (
    handoff?.token !== token ||
    handoff.phase !== 'ready' ||
    handoff.expiresAt <= Date.now() ||
    !same(handoff.session, session)
  )
    return false;
  publish({ ...handoff, phase: 'navigated' });
  return true;
}
export function takeFirstPhonePairing(token: string, session: HubSession | null): boolean {
  if (
    handoff?.token !== token ||
    handoff.phase !== 'navigated' ||
    handoff.expiresAt <= Date.now() ||
    !same(handoff.session, session)
  )
    return false;
  publish(null);
  return true;
}
