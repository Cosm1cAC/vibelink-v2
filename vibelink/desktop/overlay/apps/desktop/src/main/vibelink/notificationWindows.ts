import { app, Notification, shell } from 'electron';
import path from 'node:path';
import {
  notificationLinksMatch,
  VIBELINK_AUMID,
  VIBELINK_TOAST_CLSID,
} from './notificationIdentity.js';
export { VIBELINK_AUMID, VIBELINK_TOAST_CLSID } from './notificationIdentity.js';
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
  // The SDK names its source-mode shortcut from electron.exe metadata. Never
  // initialize its presenter unless the installer-owned compatibility link is
  // already safe; otherwise Electron can create a broken raw-executable entry.
  if (!registeredLinks()) {
    configured = false;
    console.warn(
      '[VibeLink notifications] compatible shortcuts unavailable; native registration skipped',
    );
    return;
  }
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
  if (process.platform !== 'win32')
    return { supported: Notification.isSupported(), registered: true };
  const registered = configured && registeredLinks();
  return { supported: registered ? Notification.isSupported() : false, registered };
}
function registeredLinks(): boolean {
  try {
    const programs = path.join(
      app.getPath('appData'),
      'Microsoft',
      'Windows',
      'Start Menu',
      'Programs',
    );
    const primary = shell.readShortcutLink(path.join(programs, 'VibeLink.lnk'));
    const compatibility = app.isPackaged
      ? undefined
      : shell.readShortcutLink(
          path.join(
            programs,
            path.basename(process.execPath, path.extname(process.execPath)) + '.lnk',
          ),
        );
    return notificationLinksMatch(
      primary,
      compatibility,
      {
        executable: process.execPath,
        launcher: path.join(
          process.env.LOCALAPPDATA || path.join(app.getPath('appData'), '..', 'Local'),
          'vibelink-mesh',
          'desktop',
          'notification-launcher',
        ),
      },
      !app.isPackaged,
    );
  } catch {
    return false;
  }
}
