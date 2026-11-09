import path from 'node:path';
export const VIBELINK_AUMID = 'cloud.vibelink.desktop';
export const VIBELINK_TOAST_CLSID = '{A38202ED-758B-552E-8722-E5CFC5918AFF}';
export interface NotificationShortcutIdentity {
  target: string;
  cwd?: string;
  args?: string;
  appUserModelId?: string;
  toastActivatorClsid?: string;
}
const normalized = (value: string) => path.win32.normalize(value).toLowerCase();
export function notificationLinksMatch(
  primary: NotificationShortcutIdentity | undefined,
  compatibility: NotificationShortcutIdentity | undefined,
  expected: { executable: string; launcher: string },
  sourceMode: boolean,
): boolean {
  const matches = (link: NotificationShortcutIdentity | undefined) =>
    !!link &&
    normalized(link.target) === normalized(expected.executable) &&
    normalized(link.cwd ?? '') === normalized(path.win32.dirname(expected.executable)) &&
    normalized(link.args?.replace(/^"|"$/g, '') ?? '') === normalized(expected.launcher) &&
    link.appUserModelId === VIBELINK_AUMID &&
    link.toastActivatorClsid?.replace(/[{}]/g, '').toLowerCase() ===
      VIBELINK_TOAST_CLSID.replace(/[{}]/g, '').toLowerCase();
  return matches(primary) && (!sourceMode || matches(compatibility));
}
