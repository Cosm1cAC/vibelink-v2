/** VibeLink subscriptions stay in the official CLI's native provider. API-key
 * and Magpie routes keep their independent provider configuration. */
export interface NativeCodexSubscriptionContext {
  hostPurpose?: string;
  customContextModel?: string;
  customContextWindow?: number;
  customContextProviderId?: string | null;
  deviceHostedGuestProviderId?: string;
}
interface NativeSubscriptionOptions {
  context?: NativeCodexSubscriptionContext;
  prepareContextCatalog?: (context: NativeCodexSubscriptionContext & {
    customContextModel: string; customContextWindow: number;
  }) => Promise<{ extraArgs: string[] }>;
}
function fatalSpawnError(message: string): Error {
  return Object.assign(new Error(message), { codexSpawnConfigFatal: true });
}
export async function nativeCodexSubscriptionSpawn(
  credentialMode: 'oauth-bearer' | 'gateway-key' | 'provider-oauth',
  extraArgs: readonly string[],
  extraEnv: Record<string, string>,
  options: NativeSubscriptionOptions = {},
) {
  if (process.env.CINDY_VIBELINK_EMBEDDED !== '1' || credentialMode !== 'oauth-bearer') return null;
  const context = options.context;
  if (context?.deviceHostedGuestProviderId !== undefined) {
    throw fatalSpawnError('official_cli_required: subscription sharing through a proxy is disabled in VibeLink');
  }
  let nativeContextArgs: string[] = [];
  // Cindy uses a custom-context host for an ordinary task's selected context
  // window too. Its model catalog is a native CLI option, not a model proxy.
  if (context?.hostPurpose === 'custom-context') {
    const model = context.customContextModel?.trim();
    const window = context.customContextWindow;
    if (!model || typeof window !== 'number' || !Number.isFinite(window) || window <= 0 || !options.prepareContextCatalog) {
      throw fatalSpawnError('The native Codex context model, window and catalog builder are required');
    }
    try {
      nativeContextArgs = (await options.prepareContextCatalog({
        ...context, customContextModel: model, customContextWindow: window,
      })).extraArgs;
    } catch (error) {
      throw Object.assign(error instanceof Error ? error : new Error(String(error)), { codexSpawnConfigFatal: true });
    }
  }
  return {
    extraArgs: [...extraArgs, ...nativeContextArgs, '-c', 'model_provider="openai"'],
    extraEnv: { ...extraEnv },
    codexProxyActive: false as const,
    codexDefaultModelProviderId: 'openai',
  };
}
