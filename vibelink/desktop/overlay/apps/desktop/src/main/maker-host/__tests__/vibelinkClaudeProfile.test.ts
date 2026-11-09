import { afterEach, expect, it, vi } from 'vitest';
import path from 'node:path';
import { nativeClaudeProfileEnv } from '../vibelink-claude-profile';
afterEach(() => vi.unstubAllEnvs());
it('shares one separate official profile between native Claude auth and sessions without inheriting personal provider configuration', () => {
 vi.stubEnv('CINDY_VIBELINK_EMBEDDED','1');
 const home = path.resolve('fixture-owner');
 const profile=path.join(home,'.vibelink-cli','dev','claude');
 expect(nativeClaudeProfileEnv(home)).toMatchObject({ HOME:profile,USERPROFILE:profile,CLAUDE_CONFIG_DIR:profile,APPDATA:path.join(profile,'roaming'),LOCALAPPDATA:path.join(profile,'local') });
 expect(Object.keys(nativeClaudeProfileEnv(home)).some(k=>k.startsWith('ANTHROPIC_')||k==='CLAUDE_CODE_OAUTH_TOKEN')).toBe(false);
 vi.stubEnv('CINDY_VIBELINK_EMBEDDED','0');expect(nativeClaudeProfileEnv(home)).toEqual({});
});
