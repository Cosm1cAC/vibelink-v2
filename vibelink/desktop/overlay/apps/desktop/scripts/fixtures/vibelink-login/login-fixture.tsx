import type {HubAuthInput} from '../../../src/shared/vibelinkAuth';
import type {VibeLinkDesktopBridge} from '../../../src/shared/vibelinkDesktop';
import { mountPage } from '../../ui-page/harness';
import { VibeLinkLoginPage } from '../../../src/renderer/features/vibelink/LoginPage';

// Keep this namespace stable across renders, matching contextBridge. The harness
// intentionally creates a new nested Proxy on every property read.
let bridge:VibeLinkDesktopBridge;
mountPage({
  host: (ctx) => {
    bridge={
      enabled: true,
      auth: async (input:HubAuthInput) => {
        if(input.action==='useLocalWorkspace'&&ctx.case==='setup')return new Promise<never>(()=>{});
        return { success: true, data: { status: 'signed_out', session: null, qr: ctx.case === 'scanned' ? {
          link: 'vibelink://login?purpose=desktop_login&id=fixture&s=fixture-only&u=https://fixture.invalid',
          status: 'scanned', edition: 'beta', verificationCode: '48 92', expiresAt: 9999999999999,
        } : null } };
      },
      onAuthState: () => () => undefined,
    } as VibeLinkDesktopBridge;
    return { windowBehavior: { onWindowsCloseBehaviorRequested: () => () => undefined, onLinuxCloseBehaviorRequested: () => () => undefined },vibelink:bridge };
  },
  render: () => <VibeLinkLoginPage bridge={bridge} />,
});
