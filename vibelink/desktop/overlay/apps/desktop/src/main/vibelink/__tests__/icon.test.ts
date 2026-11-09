import path from 'node:path';
import { expect, it } from 'vitest';
import { desktopIconPath } from '../icon';
it('selects mobile-derived fusion assets without changing ordinary Cindy resources', () => {
  const options = {
    fusion: true,
    packaged: false,
    platform: 'win32',
    resourcesPath: 'resources',
    moduleDirectory: path.join('app', '.vite', 'build'),
  };
  expect(desktopIconPath(options)).toBe(path.join('app', 'resources', 'vibelink-icon.ico'));
  expect(desktopIconPath({ ...options, pngOnly: true })).toBe(
    path.join('app', 'resources', 'vibelink-icon.png'),
  );
  expect(desktopIconPath({ ...options, fusion: false })).toBe(
    path.join('app', 'resources', 'icon.png'),
  );
  expect(desktopIconPath({ ...options, packaged: true, platform: 'darwin' })).toBe(
    path.join('resources', 'vibelink-icon.png'),
  );
});
