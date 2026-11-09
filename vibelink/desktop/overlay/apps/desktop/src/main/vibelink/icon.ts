import path from 'node:path';
export function desktopIconPath(options: {
  fusion: boolean;
  packaged: boolean;
  platform: string;
  resourcesPath: string;
  moduleDirectory: string;
  pngOnly?: boolean;
}): string {
  const filename = options.fusion
    ? `vibelink-icon.${options.platform === 'win32' && !options.pngOnly ? 'ico' : 'png'}`
    : 'icon.png';
  return path.join(
    options.packaged
      ? options.resourcesPath
      : path.join(options.moduleDirectory, '../../resources'),
    filename,
  );
}
