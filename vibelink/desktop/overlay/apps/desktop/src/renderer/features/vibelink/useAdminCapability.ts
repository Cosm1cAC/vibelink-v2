import { useCallback, useEffect, useRef, useState } from 'react';
import type { HubSession } from '../../../shared/vibelinkAuth';
import type { AdminInput, AdminResult } from '../../../shared/vibelinkAdmin';
export interface AdminBridge {
  admin(input: AdminInput): Promise<AdminResult>;
}
export function adminScope(session: HubSession | null): string {
  return session &&
    session.edition === 'dev' &&
    session.userId === 'owner' &&
    session.tenantId === null &&
    session.expiresAt > Date.now()
    ? `${session.issuer}/${session.edition}/${session.userId}/${session.sessionId}`
    : '';
}
export function useAdminCapability(session: HubSession | null, bridge?: AdminBridge) {
  const scope = adminScope(session),
    epoch = useRef(0);
  const [state, setState] = useState({ scope: '', allowed: false, checked: false });
  const revoke = useCallback(() => {
    epoch.current++;
    setState({ scope, allowed: false, checked: true });
  }, [scope]);
  useEffect(() => {
    const current = ++epoch.current;
    setState({ scope, allowed: false, checked: !scope || !bridge?.admin });
    if (!scope || !bridge?.admin) return;
    void bridge
      .admin({ action: 'capability' })
      .then((result) => {
        if (current === epoch.current)
          setState({
            scope,
            allowed: result.success && result.data.kind === 'capability' && result.data.allowed,
            checked: true,
          });
      })
      .catch(() => {
        if (current === epoch.current) setState({ scope, allowed: false, checked: true });
      });
    const timer = setTimeout(
      revoke,
      Math.min(2147483647, Math.max(0, (session?.expiresAt ?? 0) - Date.now())),
    );
    return () => {
      epoch.current++;
      clearTimeout(timer);
    };
  }, [scope, bridge, session?.expiresAt, revoke]);
  return {
    scope,
    allowed: !!scope && state.scope === scope && state.allowed,
    checked: !scope || (state.scope === scope && state.checked),
    revoke,
  };
}
