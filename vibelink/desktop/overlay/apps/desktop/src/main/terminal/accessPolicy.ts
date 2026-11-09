import type { IpcMainInvokeEvent } from 'electron';
import type { TerminalAccessPolicy } from '../maker-ipc/terminal-handlers.js';
import type { CreateOptions, PtyManager } from './ptyManager.js';

interface Proof { key: string; }
export interface TerminalPolicyDeps {
  identity(): { key: string; expiresAt: number } | null;
  verifySender(event: IpcMainInvokeEvent): void;
  resolveCli(intent: unknown, proof: unknown): Promise<Pick<CreateOptions, 'cwd' | 'program' | 'env'>>;
  subscribeInvalidation(listener: (force: boolean) => void): void;
}

/** Bind every terminal to a verified Hub login epoch, including ordinary shell tabs. */
export function createTerminalAccessPolicy(deps: TerminalPolicyDeps): TerminalAccessPolicy {
  const bindings = new Map<string, Proof>();
  let manager: PtyManager | undefined;
  let expiry: ReturnType<typeof setTimeout> | undefined;
  const reconcile = (force: boolean) => {
    if (expiry) clearTimeout(expiry);
    const current = deps.identity();
    for (const [id, previous] of bindings) {
      if (force || !current || current.expiresAt <= Date.now() || current.key !== previous.key) {
        manager?.dispose(id);
        bindings.delete(id);
      }
    }
    if (bindings.size && current) {
      expiry = setTimeout(() => reconcile(false), Math.min(2_147_483_647, Math.max(1, current.expiresAt - Date.now())));
      expiry.unref?.();
    }
  };
  const authorize = (event: IpcMainInvokeEvent, id?: string, prior?: unknown): Proof => {
    deps.verifySender(event);
    const current = deps.identity();
    if (!current || current.expiresAt <= Date.now()) throw new Error('请先登录开发工作区，再使用本机 CLI。');
    const previous = prior as Proof | undefined;
    if ((previous && previous.key !== current.key) ||
      (id && bindings.has(id) && bindings.get(id)!.key !== current.key)) throw new Error('CLI terminal belongs to another login session');
    return { key: current.key };
  };
  return {
    attach(value) { manager = value; deps.subscribeInvalidation(reconcile); },
    authorize,
    resolveCli: deps.resolveCli,
    created(event, id, proof) { bindings.set(id, authorize(event, id, proof)); reconcile(false); },
    disposed(id) { bindings.delete(id); reconcile(false); },
  };
}
