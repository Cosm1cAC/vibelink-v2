import { describe, expect, it } from 'vitest';
import path from 'node:path';
import fs from 'node:fs';
import os from 'node:os';
import { buildCliLaunchPlan, parseCliIntent, prepareManagedCliConfig } from '../cliLaunchPlan';

const home = path.resolve('fixture-cli-owner');
const options = { home, command: '/official-cli', baseEnv: { PATH: '/bin', ANTHROPIC_AUTH_TOKEN: 'personal-proxy', OPENAI_BASE_URL: 'http://bad', VIBELINK_TOKEN: 'admin', NODE_OPTIONS: '--require bad.js' } };

describe('embedded official CLI launch plans', () => {
  it('accepts runner intents without accepting executable paths or arbitrary args', () => {
    expect(parseCliIntent({ agent: 'claude', mode: 'login' })).toEqual({ agent: 'claude', mode: 'login' });
    expect(() => parseCliIntent({ agent: 'claude', mode: 'login', command: 'evil' })).toThrow();
    expect(() => parseCliIntent({ agent: 'opencode2', mode: 'login' })).toThrow();
    expect(() => parseCliIntent({ agent: 'unknown', mode: 'chat' })).toThrow();
  });
  it('uses the same isolated official Claude profile for login and interactive sessions', () => {
    const login = buildCliLaunchPlan({ agent: 'claude', mode: 'login' }, options);
    const chat = buildCliLaunchPlan({ agent: 'claude', mode: 'chat' }, options);
    expect(login.program.args).toEqual(['auth', 'login', '--claudeai']);
    expect(chat.program.args).toContain('--setting-sources');
    expect(login.env.CLAUDE_CONFIG_DIR).toBe(path.join(home, '.vibelink-cli/dev/claude'));
    expect(chat.env.CLAUDE_CONFIG_DIR).toBe(login.env.CLAUDE_CONFIG_DIR);
    expect(login.env.ANTHROPIC_AUTH_TOKEN).toBeUndefined();
    expect(chat.env.OPENAI_BASE_URL).toBeUndefined();
    expect(chat.env.VIBELINK_TOKEN).toBeUndefined();
    expect(chat.env.NODE_OPTIONS).toBeUndefined();
  });
  it('uses official subscription CLI login arguments and gives Codex no reverse proxy', () => {
    const login = buildCliLaunchPlan({ agent: 'codex', mode: 'login' }, options);
    const chat = buildCliLaunchPlan({ agent: 'codex', mode: 'chat' }, options);
    expect(login.program.args).toEqual(['login', '--device-auth']);
    expect(chat.program.args).toContain('model_provider="openai"');
    expect(chat.env.CODEX_HOME).toBe(path.join(home, '.vibelink-cli/dev/codex'));
    expect(chat.env.OPENAI_BASE_URL).toBeUndefined();
    const gemini = buildCliLaunchPlan({ agent: 'gemini', mode: 'chat' }, options);
    expect(gemini.program.args).toEqual([]);
    expect(gemini.env.GEMINI_CLI_HOME).toBe(path.join(home, '.vibelink-cli/dev/gemini'));
    expect(gemini.env.CODE_ASSIST_ENDPOINT).toBe('https://cloudcode-pa.googleapis.com');
    expect(gemini.env.CODE_ASSIST_API_VERSION).toBe('v1internal');
  });
  it('removes persisted Codex endpoint overrides while preserving CLI auth and history', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'vibelink-cli-config-'));
    try {
      const intent = { agent: 'codex', mode: 'chat' } as const;
      const plan = buildCliLaunchPlan(intent, { ...options, home: root });
      const profile = plan.env.CODEX_HOME!;
      fs.mkdirSync(path.join(profile, 'sessions'), { recursive: true });
      fs.writeFileSync(path.join(profile, 'config.toml'), 'openai_base_url="http://proxy"\nchatgpt_base_url="http://proxy"\nmodel_provider="custom"');
      fs.writeFileSync(path.join(profile, 'auth.json'), 'fixture-owned-by-cli');
      fs.writeFileSync(path.join(profile, 'sessions/history.jsonl'), 'fixture-history');
      prepareManagedCliConfig(intent, plan);
      const config = fs.readFileSync(path.join(profile, 'config.toml'), 'utf8');
      expect(config).toContain('model_provider = "openai"');
      expect(config).not.toMatch(/base_url|proxy|custom/);
      expect(fs.readFileSync(path.join(profile, 'auth.json'), 'utf8')).toBe('fixture-owned-by-cli');
      expect(fs.readFileSync(path.join(profile, 'sessions/history.jsonl'), 'utf8')).toBe('fixture-history');
    } finally { fs.rmSync(root, { recursive: true, force: true }); }
  });
  it('gives OpenCode2 only a model grant and one metered Magpie provider', () => {
    expect(() => buildCliLaunchPlan({ agent: 'opencode2', mode: 'chat' }, options)).toThrow(/grant/);
    const plan = buildCliLaunchPlan({ agent: 'opencode2', mode: 'chat' }, { ...options, grant: { scope: 'dev_terminal_123', token: 'model-only', gateway: 'http://127.0.0.1:18995/api/llm/v1' } });
    expect(plan.program.args).toEqual(['--standalone']);
    const config = JSON.parse(plan.env.OPENCODE_CONFIG_CONTENT!);
    expect(config.enabled_providers).toEqual(['vibelink']);
    expect(config.small_model).toBe(config.model);
    expect(config.providers.vibelink.settings.baseURL).toBe('http://127.0.0.1:18995/api/llm/v1');
    expect(plan.env.VIBELINK_MODEL_GATEWAY_TOKEN).toBe('model-only');
    expect(plan.env.VIBELINK_TOKEN).toBeUndefined();
    expect(JSON.stringify(config)).not.toContain('model-only');
    expect(() => buildCliLaunchPlan({ agent: 'opencode2', mode: 'chat' }, { ...options, grant: { scope: '../escape', token: 'x', gateway: 'https://bad' } })).toThrow();
  });
  it('enforces Gemini personal OAuth and rejects local auth/endpoint overrides without disclosing values', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'vibelink-gemini-config-'));
    try {
      const intent = { agent: 'gemini', mode: 'login' } as const;
      const plan = buildCliLaunchPlan(intent, { ...options, home: root });
      fs.mkdirSync(plan.cwd, { recursive: true });
      prepareManagedCliConfig(intent, plan);
      const policy = JSON.parse(fs.readFileSync(plan.env.GEMINI_CLI_SYSTEM_SETTINGS_PATH!, 'utf8'));
      expect(policy.security.auth).toEqual({ selectedType: 'oauth-personal', enforcedType: 'oauth-personal' });
      expect(policy.advanced.ignoreLocalEnv).toBe(true);
      fs.writeFileSync(path.join(plan.cwd, '.env'), 'GEMINI_API_KEY=fixture-private-key');
      expect(() => prepareManagedCliConfig(intent, plan)).toThrow(/官方订阅/);
      fs.unlinkSync(path.join(plan.cwd, '.env'));
      fs.writeFileSync(path.join(plan.cwd, '.env'), 'CODE_ASSIST_ENDPOINT=https://fixture-proxy.invalid');
      expect(() => prepareManagedCliConfig(intent, plan)).toThrow(/官方订阅/);
      fs.unlinkSync(path.join(plan.cwd, '.env'));
      fs.mkdirSync(path.join(plan.env.GEMINI_CLI_HOME!, '.gemini'), { recursive: true });
      fs.writeFileSync(path.join(plan.env.GEMINI_CLI_HOME!, '.gemini/settings.json'), '// official config with comments\n{"security":{"auth":{"selectedType":"oauth-personal"}},}');
      expect(() => prepareManagedCliConfig(intent, plan)).not.toThrow();
      fs.writeFileSync(path.join(plan.env.GEMINI_CLI_HOME!, '.gemini/settings.json'), JSON.stringify({ security: { auth: { selectedType: 'gemini-api-key' } } }));
      expect(() => prepareManagedCliConfig(intent, plan)).toThrow(/官方订阅/);
    } finally { fs.rmSync(root, { recursive: true, force: true }); }
  });
});
