import { useCallback, useEffect, useState } from 'react';
import type { HubAuthInput, HubAuthResult, HubAuthSnapshot, HubAuthError } from '../../../shared/vibelinkAuth';
import type { VibeLinkDesktopBridge } from '../../../shared/vibelinkDesktop';

const empty: HubAuthSnapshot = { status: 'signed_out', session: null, qr: null };

export function useHubAuth(provided?: VibeLinkDesktopBridge) {
  const bridge = provided ?? window.electronAPI?.vibelink;
  const [snapshot, setSnapshot] = useState<HubAuthSnapshot>(empty);
  const [error, setError] = useState<HubAuthError | null>(null);
  const [busy, setBusy] = useState(false);
  const apply = useCallback((result: HubAuthResult) => {
    if (result.success) { setSnapshot(result.data); setError(null); }
    else setError(result.error);
    return result;
  }, []);
  useEffect(() => {
    if (!bridge?.enabled) return;
    let disposed = false;
    const receive = (result: HubAuthResult) => { if (!disposed) apply(result); };
    const unsubscribe = bridge.onAuthState(receive);
    void bridge.auth({ action: 'snapshot' }).then(receive).catch(() => {
      if (!disposed) setError({ code: 'NETWORK_UNAVAILABLE', message: '无法读取登录状态，请重试', recoverable: true });
    });
    return () => { disposed = true; unsubscribe(); };
  }, [bridge, apply]);
  const execute = useCallback(async (input: HubAuthInput) => {
    if (!bridge?.enabled) return;
    setBusy(true); setError(null);
    try { return apply(await bridge.auth(input)); }
    catch { setError({ code: 'LOGIN_FAILED', message: '登录暂时未完成，请重试', recoverable: true }); }
    finally { setBusy(false); }
  }, [bridge, apply]);
  return { snapshot, error, busy, execute };
}
