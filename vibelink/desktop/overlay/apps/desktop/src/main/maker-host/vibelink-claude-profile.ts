import os from 'node:os';
import path from 'node:path';

/** One official CLI profile shared by VibeLink desktop and Agent, without personal provider overrides. */
export function nativeClaudeProfileEnv(home = os.homedir()): Record<string, string> {
  if (process.env.CINDY_VIBELINK_EMBEDDED !== '1') return {};
  const profile = path.join(home, '.vibelink-cli', 'dev', 'claude');
  return {
    HOME: profile,
    USERPROFILE: profile,
    CLAUDE_CONFIG_DIR: profile,
    APPDATA: path.join(profile, 'roaming'),
    LOCALAPPDATA: path.join(profile, 'local'),
    XDG_CONFIG_HOME: path.join(profile, 'config'),
    XDG_DATA_HOME: path.join(profile, 'data'),
    XDG_STATE_HOME: path.join(profile, 'state'),
    XDG_CACHE_HOME: path.join(profile, 'cache'),
  };
}
