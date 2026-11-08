import { mountPage } from '../../ui-page/harness';
import { VibeLinkLoginPage } from '../../../src/renderer/features/vibelink/LoginPage';

mountPage({
  host: (ctx) => ({
    windowBehavior: { onWindowsCloseBehaviorRequested: () => () => undefined, onLinuxCloseBehaviorRequested: () => () => undefined },
    vibelink: {
      enabled: true,
      auth: async () => ({ success: true, data: { status: 'signed_out', session: null, qr: ctx.case === 'scanned' ? {
        link: 'vibelink://login?purpose=desktop_login&id=fixture&s=fixture-only&u=https://fixture.invalid',
        status: 'scanned', edition: 'beta', verificationCode: '48 92', expiresAt: 9999999999999,
      } : null } }),
      onAuthState: () => () => undefined,
    },
  }),
  render: () => <VibeLinkLoginPage />,
});
