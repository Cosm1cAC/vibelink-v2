import { afterEach, expect, it, vi } from 'vitest';
// New Hub identities must never inherit the owner's local model execution.
// Existing subscription tests have no beta principal; smallest input is a beta
// verified session. Unit-tier production gate is also used by real Maker start.
import { assertVibeLinkLocalExecution, setVerifiedHubIdentity } from '../verifiedIdentity';

afterEach(() => { setVerifiedHubIdentity(null); vi.unstubAllEnvs(); });

it('blocks the local engine for signed-out and beta identities without disabling dev official CLI execution', () => {
  vi.stubEnv('CINDY_VIBELINK_EMBEDDED', '1');
  expect(() => assertVibeLinkLocalExecution()).toThrow('hub_execution_required');
  const session = { userId: 'owner', label: 'Owner', tenantId: null, edition: 'dev' as const, sessionId: 's_fake', expiresAt: 9999999999999, issuer: `hub_${'a'.repeat(24)}`, hubId: `hub_${'a'.repeat(24)}`, permissions: ['client'] as ['client'] };
  setVerifiedHubIdentity({ ...session, edition: 'beta', tenantId: 'beta_01', userId: 'beta_01' });
  expect(() => assertVibeLinkLocalExecution()).toThrow('hub_execution_required');
  setVerifiedHubIdentity(session);
  expect(() => assertVibeLinkLocalExecution()).not.toThrow();
  vi.stubEnv('CINDY_VIBELINK_EMBEDDED', '0');
  setVerifiedHubIdentity(null);
  expect(() => assertVibeLinkLocalExecution()).not.toThrow();
});
