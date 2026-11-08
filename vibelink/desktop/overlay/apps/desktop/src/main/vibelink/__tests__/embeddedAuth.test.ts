import { expect, it } from 'vitest';
// The actual HTTP authorization gate must reject missing Main proof and beta
// admin actions. The prior HTTP test had only trusted local-admin behavior;
// smallest failing inputs are no proof/null identity/beta action (unit tier).
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const embedded = require('../embeddedServer.cjs');

it('requires an IPC-only proof and a verified current session for the HTTP control fallback', () => {
  const credential = { hub: 'https://hub.vibelink.cloud', token: 'fake-client', localManagement: false };
  expect(embedded.authorizeApiRequest(undefined, 'fake-ipc-proof', credential, 'state')).toBe(false);
  expect(embedded.authorizeApiRequest('fake-ipc-proof', 'fake-ipc-proof', null, 'state')).toBe(false);
  expect(embedded.authorizeApiRequest('fake-ipc-proof', 'fake-ipc-proof', credential, 'state')).toBe(true);
  expect(embedded.authorizeApiRequest('fake-ipc-proof', 'fake-ipc-proof', credential, 'action')).toBe(false);
  expect(embedded.authorizeApiRequest('fake-ipc-proof', 'fake-ipc-proof', { ...credential, localManagement: true }, 'action')).toBe(true);
});
