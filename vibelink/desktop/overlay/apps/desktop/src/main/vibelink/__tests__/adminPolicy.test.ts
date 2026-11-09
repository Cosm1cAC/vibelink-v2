import { expect, it } from 'vitest';
import { localAdminIdentity } from '../adminPolicy';
it('requires an unexpired verified and committed localhost owner before granting beta management', () => {
 const session = { userId: 'owner', label: 'Owner', tenantId: null, edition: 'dev' as const, sessionId: 's1', expiresAt: 9000, permissions: ['client'] as ['client'], issuer: 'hub_fixture', hubId: 'hub_fixture' };
 const context = { enabled: true, pending: false, origin: 'http://127.0.0.1:18995', session, verified: session, mode: 'hub', dataOwnerId: 'local-v1', committedOwner: 'local-v1', generation: 1 };
 expect(localAdminIdentity(context, 1000)).toEqual({ scope: 'hub_fixture|dev|owner', epoch: 's1/1' });
 for (const patch of [{ enabled: false }, { pending: true }, { origin: 'https://hub.vibelink.cloud' }, { mode: 'signed-out' }, { dataOwnerId: 'some-tenant' }, { committedOwner: null }, { verified: null }, { verified: { ...session, sessionId: 'other' } }, { verified: { ...session, tenantId: 'beta_01' } }, { verified: { ...session, expiresAt: 1000 } }, { session: { ...session, tenantId: 'beta_01' } }, { session: { ...session, edition: 'beta' as const } }, { session: { ...session, expiresAt: 1000 } }]) {
  expect(localAdminIdentity({ ...context, ...patch }, 1000)).toBeNull();
 }
});
