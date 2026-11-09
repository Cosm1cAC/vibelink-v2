import type { HubSession } from '../../shared/vibelinkAuth';
import type { AdminIdentity } from './adminService';
export interface AdminContext {
 enabled: boolean; pending: boolean; origin: string; session: HubSession | null; verified: HubSession | null;
 mode: string; dataOwnerId: string | null; committedOwner: string | null; generation: number;
}
export function localAdminIdentity(c: AdminContext, now: number): AdminIdentity | null {
 const s = c.session, v = c.verified;
 if (!c.enabled || c.pending || c.origin !== 'http://127.0.0.1:18995' || !s || !v ||
     s.edition !== 'dev' || s.userId !== 'owner' || s.tenantId !== null || s.expiresAt <= now ||
     !s.permissions.includes('client') || c.mode !== 'hub' || c.dataOwnerId !== 'local-v1' || c.committedOwner !== 'local-v1' ||
     v.tenantId !== null || v.expiresAt <= now || !v.permissions.includes('client') || v.sessionId !== s.sessionId || v.issuer !== s.issuer || v.edition !== s.edition || v.userId !== s.userId)
   return null;
 return { scope: s.issuer + '|dev|owner', epoch: s.sessionId + '/' + c.generation };
}
