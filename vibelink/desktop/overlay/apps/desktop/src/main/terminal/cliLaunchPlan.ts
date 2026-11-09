import path from 'node:path';
import fs from 'node:fs';
import JSON5 from 'json5';
import type { TerminalCliIntent } from '../../shared/terminal-bridge.js';

export interface CliLaunchPlan {
  cwd: string;
  program: { command: string; args: string[]; displayName: string };
  env: Record<string, string | undefined>;
}

/** Only the VibeLink-managed Codex config is replaced; auth and session files remain owned by CLI. */
export function prepareManagedCliConfig(intent: TerminalCliIntent, plan: CliLaunchPlan): void {
  if (intent.agent === 'gemini') {
    const home = plan.env.GEMINI_CLI_HOME!;
    assertGeminiSubscriptionFiles(home, plan.cwd);
    fs.mkdirSync(home, { recursive: true });
    fs.writeFileSync(plan.env.GEMINI_CLI_SYSTEM_SETTINGS_PATH!, JSON.stringify({
      security: { auth: { selectedType: 'oauth-personal', enforcedType: 'oauth-personal' } },
      advanced: { ignoreLocalEnv: true, excludedEnvVars: GEMINI_PROVIDER_KEYS },
    }));
    fs.writeFileSync(plan.env.GEMINI_CLI_SYSTEM_DEFAULTS_PATH!, '{}');
    return;
  }
  if (intent.agent !== 'codex') return;
  const home = plan.env.CODEX_HOME;
  if (!home) throw new Error('Missing managed Codex profile');
  fs.mkdirSync(home, { recursive: true });
  fs.writeFileSync(path.join(home, 'config.toml'), 'model_provider = "openai"\nforced_login_method = "chatgpt"\ncli_auth_credentials_store = "file"\n');
}

const GEMINI_PROVIDER_KEYS = ['GEMINI_API_KEY', 'GOOGLE_API_KEY', 'GOOGLE_GEMINI_BASE_URL', 'CODE_ASSIST_ENDPOINT', 'CODE_ASSIST_API_VERSION',
  'GOOGLE_GENAI_USE_VERTEXAI', 'GOOGLE_CLOUD_PROJECT', 'GOOGLE_CLOUD_LOCATION',
  'GOOGLE_APPLICATION_CREDENTIALS', 'GEMINI_CLI_HOME', 'GEMINI_CLI_SYSTEM_SETTINGS_PATH', 'GEMINI_CLI_SYSTEM_DEFAULTS_PATH'];

function assertGeminiSubscriptionFiles(home: string, cwd: string): void {
  const directories = new Set([home]);
  for (let directory = cwd; ; directory = path.dirname(directory)) {
    directories.add(directory);
    if (directory === home || path.dirname(directory) === directory) break;
  }
  const overrides = (value: unknown): boolean => !!value && typeof value === 'object' && Object.entries(value).some(([key, field]) => {
    const name = key.replace(/[_-]/g, '').toLowerCase();
    return (['baseurl', 'apikey'].includes(name) && !!field) ||
      (['selectedtype', 'enforcedtype'].includes(name) && !!field && field !== 'oauth-personal') || overrides(field);
  });
  for (const directory of directories) {
    for (const relative of ['.env', '.gemini/.env']) {
      const filename = path.join(directory, relative);
      if (!fs.existsSync(filename)) continue;
      const content = fs.readFileSync(filename, 'utf8');
      if (content.split(/\r?\n/).some(line => {
        const assignment = line.match(/^\s*(?:export\s+)?([A-Z_][A-Z0-9_]*)\s*=\s*(.*?)\s*$/);
        return !!assignment && GEMINI_PROVIDER_KEYS.includes(assignment[1]) && !!assignment[2].replace(/^["']|["']$/g, '');
      })) throw new Error('Gemini 工作区环境覆盖了官方订阅配置；请移除 provider/auth 覆盖后重试。');
    }
    const settings = path.join(directory, '.gemini/settings.json');
    if (fs.existsSync(settings)) {
      let value: unknown;
      try { value = JSON5.parse(fs.readFileSync(settings, 'utf8')); }
      catch { throw new Error('Gemini 配置无法解析；请修复 settings.json 后重试。'); }
      if (overrides(value)) throw new Error('Gemini 配置覆盖了官方订阅认证或地址；请移除覆盖后重试。');
    }
  }
}

export function parseCliIntent(value: unknown): TerminalCliIntent {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid CLI intent');
  const record = value as Record<string, unknown>;
  if (Object.keys(record).some(key => !['agent', 'mode'].includes(key)) ||
      !['codex', 'opencode2', 'claude', 'gemini'].includes(String(record.agent)) ||
      !['chat', 'login'].includes(String(record.mode)) ||
      (record.agent === 'opencode2' && record.mode === 'login')) throw new Error('Invalid CLI intent');
  return record as unknown as TerminalCliIntent;
}

const INHERITED_ENV = new Set(['PATH', 'PATHEXT', 'SYSTEMROOT', 'WINDIR', 'COMSPEC', 'SYSTEMDRIVE',
  'TEMP', 'TMP', 'TMPDIR', 'LANG', 'LC_ALL', 'LC_CTYPE', 'HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY', 'NO_PROXY']);

export function buildCliLaunchPlan(intent: TerminalCliIntent, options: {
  home: string;
  command: string;
  baseEnv: NodeJS.ProcessEnv;
  networkEnv?: Record<string, string>;
  grant?: { scope: string; token: string; gateway: string };
}): CliLaunchPlan {
  const env: Record<string, string | undefined> = {};
  // Undefined entries explicitly remove inherited Electron/admin/provider secrets in PtyManager.
  for (const [key, value] of Object.entries(options.baseEnv)) env[key] = INHERITED_ENV.has(key.toUpperCase()) ? value : undefined;
  const root = path.join(options.home, '.vibelink-cli');
  let home = path.join(root, 'dev', intent.agent);
  const args: string[] = [];
  const names = { codex: 'Codex', opencode2: 'OpenCode2 · DeepSeek', claude: 'Claude Code', gemini: 'Gemini CLI' };
  if (intent.agent === 'codex') {
    env.CODEX_HOME = home;
    if (intent.mode === 'login') args.push('login', '--device-auth');
    else args.push('-c', 'model_provider="openai"', '-c', 'forced_login_method="chatgpt"');
  } else if (intent.agent === 'claude') {
    env.CLAUDE_CONFIG_DIR = home;
    Object.assign(env, options.networkEnv);
    if (intent.mode === 'login') args.push('auth', 'login', '--claudeai');
    else args.push('--setting-sources', '', '--settings', '{}', '--strict-mcp-config');
  } else if (intent.agent === 'opencode2') {
    const grant = options.grant;
    if (!grant?.token || !/^dev_terminal_[a-zA-Z0-9_]{1,80}$/.test(grant.scope) ||
        grant.gateway !== 'http://127.0.0.1:18995/api/llm/v1') throw new Error('Missing or invalid DeepSeek model grant');
    home = path.join(root, 'scopes', grant.scope, 'opencode2');
    const model = 'vibelink/auto/deepseek-v4.1-flash';
    env.VIBELINK_MODEL_GATEWAY_TOKEN = grant.token;
    env.OPENCODE_CONFIG_PROJECT_DISABLE = 'true';
    env.OPENCODE_DISABLE_CLAUDE_CODE = 'true';
    env.OPENCODE_CONFIG_CONTENT = JSON.stringify({
      model, small_model: model, enabled_providers: ['vibelink'], update: 'disable', snapshots: false,
      providers: { vibelink: { name: 'VibeLink DeepSeek', env: ['VIBELINK_MODEL_GATEWAY_TOKEN'],
        package: '@opencode/ai/providers/openai-compatible', settings: { baseURL: grant.gateway },
        models: { 'auto/deepseek-v4.1-flash': { name: 'DeepSeek' } } } },
      policies: [ { action: 'provider.use', resource: '*', effect: 'deny' },
        { action: 'provider.use', resource: 'vibelink', effect: 'allow' },
        { action: 'plugin.use', resource: '*', effect: 'deny' }, { action: 'mcp.use', resource: '*', effect: 'deny' } ],
    });
    args.push('--standalone');
  } else if (intent.agent === 'gemini') {
    env.GEMINI_CLI_HOME = home;
    env.CODE_ASSIST_ENDPOINT = 'https://cloudcode-pa.googleapis.com';
    env.CODE_ASSIST_API_VERSION = 'v1internal';
    env.GEMINI_CLI_SYSTEM_SETTINGS_PATH = path.join(home, 'managed-official-settings.json');
    env.GEMINI_CLI_SYSTEM_DEFAULTS_PATH = path.join(home, 'managed-official-defaults.json');
  }
  Object.assign(env, {
    HOME: home, USERPROFILE: home, APPDATA: path.join(home, 'roaming'), LOCALAPPDATA: path.join(home, 'local'),
    XDG_CONFIG_HOME: path.join(home, 'config'), XDG_DATA_HOME: path.join(home, 'data'),
    XDG_STATE_HOME: path.join(home, 'state'), XDG_CACHE_HOME: path.join(home, 'cache'),
  });
  // Run in a neutral profile workspace so project .env/settings cannot replace the official provider.
  return { cwd: path.join(home, 'workspace'), env,
    program: { command: options.command, args, displayName: `${names[intent.agent]}${intent.mode === 'login' ? ' · Login' : ''}` } };
}
