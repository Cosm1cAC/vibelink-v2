import { afterEach, expect, test, vi } from 'vitest';
import { nativeCodexSubscriptionSpawn } from '../vibelink-subscription-policy';
afterEach(() => vi.unstubAllEnvs());
test('fused Codex OAuth uses the official provider and disables the subscription proxy', async () => {
  vi.stubEnv('CINDY_VIBELINK_EMBEDDED', '1');
  const config = await nativeCodexSubscriptionSpawn('oauth-bearer', ['-c', 'mcp_servers.fixture.enabled=true'], {});
  expect(config).toEqual({
    extraArgs: ['-c', 'mcp_servers.fixture.enabled=true', '-c', 'model_provider="openai"'],
    extraEnv: {}, codexProxyActive: false, codexDefaultModelProviderId: 'openai',
  });
});

test('API-key routes and non-fused Cindy keep their existing spawn configuration', async () => {
  vi.stubEnv('CINDY_VIBELINK_EMBEDDED', '1');
  expect(await nativeCodexSubscriptionSpawn('gateway-key', [], {})).toBeNull();
  expect(await nativeCodexSubscriptionSpawn('provider-oauth', [], {})).toBeNull();
  vi.stubEnv('CINDY_VIBELINK_EMBEDDED', '0');
  expect(await nativeCodexSubscriptionSpawn('oauth-bearer', [], {})).toBeNull();
});

test('default GPT native spawn accepts the complete isolated custom-context host and passes its catalog to the CLI', async () => {
  vi.stubEnv('CINDY_VIBELINK_EMBEDDED', '1');
  const context = {
    credentialMode: 'oauth-bearer' as const,
    hostPurpose: 'custom-context' as const,
    localAuthPolicy: 'isolated' as const,
    hostScopeKey: 'local-custom-context:task77811de2:default:1',
    customContextHostKey: 'local-custom-context:task77811de2:default:1',
    accountHostKey: 'openai-account:default',
    codexHome: '/fixture/profile/codex', runtimeCodexHome: '/fixture/profile/codex',
    customContextProviderId: 'openai', customContextModel: 'gpt-6.1-sol',
    customContextWindow: 1_050_000,
  };
  const prepareContextCatalog = vi.fn(async () => ({ extraArgs: ['-c', 'model_catalog_json="/fixture/native/models.json"'] }));
  const config = await nativeCodexSubscriptionSpawn('oauth-bearer', [
    '-c', 'mcp_servers.cindy_memory.enabled=true', '-c', 'features.browser_use=true',
  ], { CINDY_BRIDGE_INSTANCE_ID: 'fixture-instance' }, { context, prepareContextCatalog });
  expect(prepareContextCatalog).toHaveBeenCalledExactlyOnceWith(context);
  expect(config?.codexProxyActive).toBe(false);
  expect(config?.codexDefaultModelProviderId).toBe('openai');
  expect(config?.extraArgs).toEqual([
    '-c', 'mcp_servers.cindy_memory.enabled=true', '-c', 'features.browser_use=true',
    '-c', 'model_catalog_json="/fixture/native/models.json"', '-c', 'model_provider="openai"',
  ]);
  expect(config?.extraEnv).toEqual({ CINDY_BRIDGE_INSTANCE_ID: 'fixture-instance' });
});

test('shared subscription proxy is refused before building native context metadata', async () => {
  vi.stubEnv('CINDY_VIBELINK_EMBEDDED', '1');
  const prepareContextCatalog = vi.fn(async () => ({ extraArgs: [] }));
  await expect(nativeCodexSubscriptionSpawn('oauth-bearer', [], {}, {
    context: { hostPurpose: 'custom-context', customContextModel: 'gpt-6.1-sol', customContextWindow: 1_050_000, deviceHostedGuestProviderId: 'shared-openai' },
    prepareContextCatalog,
  })).rejects.toMatchObject({ codexSpawnConfigFatal: true, message: expect.stringContaining('subscription sharing') });
  expect(prepareContextCatalog).not.toHaveBeenCalled();
});
