import { expect, it, vi } from 'vitest';
import { notificationLinksMatch } from '../notificationIdentity';
const expected = {
  executable: 'D:\\VibeLink\\electron.exe',
  launcher: 'C:\\Local\\vibelink-mesh\\desktop\\notification-launcher',
};
const link = {
  target: expected.executable,
  cwd: 'D:\\VibeLink',
  args: `"${expected.launcher}"`,
  appUserModelId: 'cloud.vibelink.desktop',
  toastActivatorClsid: '{A38202ED-758B-552E-8722-E5CFC5918AFF}',
};
const native = vi.hoisted(() => ({
  compatibility: false,
  created: 0,
  supportedChecks: 0,
  events: [] as string[],
}));
vi.mock('electron', async () => {
  const path = (await import('node:path')).default;
  return {
    app: {
      isPackaged: false,
      getPath: (key: string) => (key === 'appData' ? 'C:\\Roaming' : 'C:\\ExistingProfile'),
      setPath: () => native.events.push('path'),
      setName: () => native.events.push('name'),
      setAppUserModelId: () => {},
      setToastActivatorCLSID: () => {},
    },
    Notification: class {
      static isSupported() {
        native.supportedChecks++;
        return true;
      }
      constructor() {
        native.created++;
      }
      close() {}
    },
    shell: {
      readShortcutLink: (file: string) => {
        if (!file.toLowerCase().endsWith('vibelink.lnk') && !native.compatibility)
          throw Error('foreign or absent compatibility link');
        return {
          target: process.execPath,
          cwd: path.dirname(process.execPath),
          args: `"${path.join(process.env.LOCALAPPDATA || 'C:\\Local', 'vibelink-mesh', 'desktop', 'notification-launcher')}"`,
          appUserModelId: 'cloud.vibelink.desktop',
          toastActivatorClsid: '{A38202ED-758B-552E-8722-E5CFC5918AFF}',
        };
      },
    },
  };
});
it.runIf(process.platform === 'win32')(
  'skips native initialization entirely until the installer-owned compatibility link matches',
  async () => {
    const module = await import('../notificationWindows');
    module.configureHubNotificationIdentity();
    expect(native.created).toBe(0);
    expect(native.supportedChecks).toBe(0);
    expect(module.hubNotificationCapability().registered).toBe(false);
    native.compatibility = true;
    module.configureHubNotificationIdentity();
    expect(native.created).toBe(1);
    expect(module.hubNotificationCapability().registered).toBe(true);
    expect(native.events.slice(0, 2)).toEqual(['path', 'name']);
  },
);
it('requires a matching SDK compatibility shortcut before source-mode registration', () => {
  expect(notificationLinksMatch(link, undefined, expected, true)).toBe(false);
  expect(
    notificationLinksMatch(link, { ...link, appUserModelId: 'third.party' }, expected, true),
  ).toBe(false);
  expect(notificationLinksMatch(link, { ...link, args: '' }, expected, true)).toBe(false);
  expect(notificationLinksMatch(link, link, expected, true)).toBe(true);
});
it('normalizes Windows identity casing but rejects unrelated targets and raw Electron arguments', () => {
  expect(
    notificationLinksMatch(
      { ...link, target: link.target.toUpperCase() },
      { ...link, toastActivatorClsid: link.toastActivatorClsid.toLowerCase() },
      expected,
      true,
    ),
  ).toBe(true);
  expect(
    notificationLinksMatch({ ...link, target: 'C:\\Other\\electron.exe' }, link, expected, true),
  ).toBe(false);
  expect(notificationLinksMatch(link, { ...link, cwd: 'C:\\Other' }, expected, true)).toBe(false);
  expect(
    notificationLinksMatch(
      { ...link, args: `"${expected.launcher}" --other` },
      link,
      expected,
      true,
    ),
  ).toBe(false);
});
