import { app, Notification, shell } from 'electron';
import path from 'node:path';
export const VIBELINK_AUMID = 'cloud.vibelink.desktop';
export const VIBELINK_TOAST_CLSID = '{A38202ED-758B-552E-8722-E5CFC5918AFF}';
let configured = false;
let unshownRegistrationWarmup: Notification | null = null;
/** Called only for fusion mode, before any native notification is created. */
export function configureHubNotificationIdentity(): void {
  if (process.platform !== 'win32') return;
  // Freeze the existing profile before changing Electron's application identity.
  app.setPath('userData', app.getPath('userData'));
  app.setName('VibeLink');
  app.setAppUserModelId(VIBELINK_AUMID);
  app.setToastActivatorCLSID(VIBELINK_TOAST_CLSID);
  configured = true;
  // Electron 42 registers the COM presenter lazily in the constructor. Never
  // call show here: registration is independent of notification opt-in.
  try {
    if (Notification.isSupported()) {
      unshownRegistrationWarmup = new Notification({ title: 'VibeLink', body: '', silent: true });
      unshownRegistrationWarmup.close();
    }
  } catch {
    configured = false;
  }
  console.log(
    '[VibeLink notifications] AUMID=%s activator=%s',
    VIBELINK_AUMID,
    VIBELINK_TOAST_CLSID,
  );
}
export function hubNotificationCapability(): { supported: boolean; registered: boolean } {
  const supported = Notification.isSupported();
  if (process.platform !== 'win32') return { supported, registered: true };
  try {
    const details = shell.readShortcutLink(
      path.join(
        app.getPath('appData'),
        'Microsoft',
        'Windows',
        'Start Menu',
        'Programs',
        'VibeLink.lnk',
      ),
    );
    return {
      supported,
      registered:
        configured &&
        path.resolve(details.target).toLowerCase() ===
          path.resolve(process.execPath).toLowerCase() &&
        path.resolve(details.cwd ?? '').toLowerCase() ===
          path.dirname(process.execPath).toLowerCase() &&
        details.args?.replace(/^"|"$/g, '').toLowerCase() ===
          path
            .join(
              process.env.LOCALAPPDATA || path.join(app.getPath('appData'), '..', 'Local'),
              'vibelink-mesh',
              'desktop',
              'notification-launcher',
            )
            .toLowerCase() &&
        details.appUserModelId === VIBELINK_AUMID &&
        details.toastActivatorClsid?.replace(/[{}]/g, '').toLowerCase() ===
          VIBELINK_TOAST_CLSID.replace(/[{}]/g, '').toLowerCase(),
    };
  } catch {
    return { supported, registered: false };
  }
}
