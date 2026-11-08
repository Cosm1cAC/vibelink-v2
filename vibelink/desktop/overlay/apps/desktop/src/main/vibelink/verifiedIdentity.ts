import type { HubSession } from '../../shared/vibelinkAuth.js';

let verified: HubSession | null = null;

/** Main-owned projection, set only after server verification and owner commit. */
export function setVerifiedHubIdentity(session: HubSession | null): void { verified = session; }
export function getVerifiedHubIdentity(): HubSession | null { return verified; }

export function assertVibeLinkLocalExecution(): void {
  if (process.env.CINDY_VIBELINK_EMBEDDED !== '1') return;
  if (!verified || verified.edition !== 'dev' || verified.expiresAt <= Date.now()) {
    throw new Error('hub_execution_required: 请在 VibeLink Hub 工作区发起任务；当前身份不能使用本机订阅引擎。');
  }
}
