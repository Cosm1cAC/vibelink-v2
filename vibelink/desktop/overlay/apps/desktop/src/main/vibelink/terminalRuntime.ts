import { net } from 'electron';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { getCachedBinaryStatus, getReadyBinaryPath } from '../agent-binaries/index.js';
import { assertTrustedAppRendererEvent } from '../security/trustedAppRenderer.js';
import { onAppSessionInvalidated } from '../appSessionState.js';
import { claudeCliNetworkEnv } from '../maker-host/claude-native-cli.js';
import { createTerminalAccessPolicy } from '../terminal/accessPolicy.js';
import { buildCliLaunchPlan, parseCliIntent, prepareManagedCliConfig } from '../terminal/cliLaunchPlan.js';
import { currentHubCredential, isVibeLinkMode, onHubAuthChanged } from './authRuntime.js';
import { currentHubWorkCredential } from './workRuntime.js';
import { getVerifiedHubIdentity } from './verifiedIdentity.js';
import type { TerminalAccessPolicy } from '../maker-ipc/terminal-handlers.js';

function identity() {
  const work = currentHubWorkCredential();
  const verified = getVerifiedHubIdentity();
  return work && verified?.edition === 'dev' && work.origin === 'http://127.0.0.1:18995'
    ? { key: `${work.scope}/${work.epoch}`, expiresAt: verified.expiresAt } : null;
}

function executable(agent: 'codex' | 'opencode2' | 'claude' | 'gemini'): { command: string; prefix: string[]; node: boolean } {
  const home = os.homedir();
  if (agent === 'codex' || agent === 'claude') {
    const kind = agent === 'codex' ? 'codex' : 'claude-code';
    const managed = getReadyBinaryPath(kind) || getCachedBinaryStatus(kind).binaryPath;
    if (managed && fs.existsSync(managed)) return { command: managed, prefix: [], node: false };
  }
  const candidates: string[] = agent === 'opencode2'
    ? [path.join(home, 'AppData/Roaming/npm/node_modules/@opencode/cli/bin/opencode.exe'), path.join(home, '.opencode/bin/opencode2')]
    : agent === 'claude' ? [path.join(home, '.local/bin', process.platform === 'win32' ? 'claude.exe' : 'claude')]
    : agent === 'gemini' ? [path.join(home, 'AppData/Roaming/npm/node_modules/@google/gemini-cli/dist/index.js')]
    : [];
  for (const candidate of candidates) {
    if (fs.existsSync(candidate)) return candidate.endsWith('.js')
      ? { command: process.execPath, prefix: [candidate], node: true }
      : { command: candidate, prefix: [], node: false };
  }
  if (process.platform !== 'win32') {
    for (const directory of (process.env.PATH || '').split(path.delimiter)) {
      const candidate = path.join(directory, agent);
      if (fs.existsSync(candidate)) return { command: candidate, prefix: [], node: false };
    }
  }
  throw new Error(`未安装 ${agent} 官方 CLI，请安装后重新打开终端。`);
}

/** Verified personal dev capability; beta execution remains in the tenant worker. */
export function createVibeLinkTerminalPolicy(): TerminalAccessPolicy | undefined {
  if (!isVibeLinkMode()) return undefined;
  return createTerminalAccessPolicy({
    identity,
    verifySender: assertTrustedAppRendererEvent,
    subscribeInvalidation(listener) {
      onHubAuthChanged(() => listener(false));
      onAppSessionInvalidated(() => listener(true));
    },
    async resolveCli(raw, proof) {
      const intent = parseCliIntent(raw);
      const initial = identity();
      if (!initial || initial.key !== (proof as { key?: string })?.key) throw new Error('CLI login session changed');
      const binary = executable(intent.agent);
      let grant: { scope: string; token: string; gateway: string } | undefined;
      if (intent.agent === 'opencode2') {
        const credential = currentHubCredential();
        if (!credential || credential.origin !== 'http://127.0.0.1:18995') throw new Error('DeepSeek requires the development Hub');
        const response = await net.fetch(`${credential.origin}/api/cli/launch-grant`, {
          method: 'POST', credentials: 'omit', redirect: 'error',
          headers: { Authorization: `Bearer ${credential.token}`, 'Content-Type': 'application/json' },
          body: '{}', signal: AbortSignal.timeout(10000),
        });
        if (!response.ok) throw new Error(`DeepSeek CLI 授权失败（HTTP ${response.status}），请更新 Hub 并重新登录。`);
        const result = await response.json() as { agent?: string; model?: string; token?: string; scope?: string };
        if (result.agent !== 'opencode2' || result.model !== 'auto/deepseek-v4.1-flash' ||
          typeof result.token !== 'string' || result.token.length > 2048 || typeof result.scope !== 'string') throw new Error('Invalid model-only CLI grant');
        grant = { scope: result.scope, token: result.token, gateway: `${credential.origin}/api/llm/v1` };
      }
      const networkEnv = intent.agent === 'claude' ? await claudeCliNetworkEnv() : undefined;
      if (identity()?.key !== initial.key) throw new Error('CLI login session changed');
      const plan = buildCliLaunchPlan(intent, { home: os.homedir(), command: binary.command, baseEnv: process.env, grant, networkEnv });
      plan.program.args.unshift(...binary.prefix);
      if (binary.node) plan.env.ELECTRON_RUN_AS_NODE = '1';
      for (const key of ['HOME', 'APPDATA', 'LOCALAPPDATA', 'XDG_CONFIG_HOME', 'XDG_DATA_HOME', 'XDG_STATE_HOME', 'XDG_CACHE_HOME']) {
        fs.mkdirSync(plan.env[key]!, { recursive: true });
      }
      fs.mkdirSync(plan.cwd, { recursive: true });
      prepareManagedCliConfig(intent, plan);
      return plan;
    },
  });
}
