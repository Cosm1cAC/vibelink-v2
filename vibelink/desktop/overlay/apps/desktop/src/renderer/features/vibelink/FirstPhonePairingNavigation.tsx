import { useEffect, useSyncExternalStore } from 'react';
import { useAuth } from '@/contexts/AuthContext';
import { useHubAuth } from './useHubAuth';
import {
  cancelFirstPhonePairing,
  getFirstPhonePairing,
  markFirstPhoneNavigation,
  subscribeFirstPhonePairing,
} from './firstPhonePairing';
/** UI hint only; the existing Main hub-pair management guard is authoritative. */
export function FirstPhonePairingNavigation() {
  const request = useSyncExternalStore(
    subscribeFirstPhonePairing,
    getFirstPhonePairing,
    () => null,
  );
  const { snapshot } = useHubAuth();
  const auth = useAuth();
  useEffect(() => {
    if (!request) return;
    const timer = setTimeout(
      () => cancelFirstPhonePairing(request.token),
      Math.max(0, request.expiresAt - Date.now()),
    );
    return () => clearTimeout(timer);
  }, [request]);
  useEffect(() => {
    if (!request || request.phase === 'waiting') return;
    if (request.expiresAt <= Date.now()) {
      cancelFirstPhonePairing(request.token);
      return;
    }
    const actual = snapshot.session,
      expected = request.session;
    if (
      actual &&
      expected &&
      (actual.issuer !== expected.issuer ||
        actual.edition !== expected.edition ||
        actual.userId !== expected.userId ||
        actual.sessionId !== expected.sessionId)
    ) {
      cancelFirstPhonePairing(request.token);
      return;
    }
    if (auth.mode !== 'hub' || auth.dataOwnerId !== 'local-v1' || auth.isInitializing) return;
    if (markFirstPhoneNavigation(request.token, actual))
      window.location.hash = `/vibelink?firstPhone=${encodeURIComponent(request.token)}`;
  }, [request, snapshot.session, auth.mode, auth.dataOwnerId, auth.isInitializing]);
  return null;
}
