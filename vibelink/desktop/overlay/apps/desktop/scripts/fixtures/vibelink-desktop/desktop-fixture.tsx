import { mountPage } from '../../ui-page/harness';
import { VibeLinkControlCenter } from '../../../src/renderer/features/vibelink/VibeLinkControlCenter';

// Substitute only the native local-service boundary. Product component, controls,
// polling and error handling are real; this fixture cannot send system commands.
const nativeFetch = window.fetch.bind(window);
window.fetch = async (input, init) => {
  const url = String(input);
  if (url === '/api/vibelink/state') return Response.json({
    hubOk: true, hubError: null, hubUrl: 'http://127.0.0.1:18995', hasToken: true,
    devices: [{ id: 'pc', hostname: 'desktop', os: 'windows', online: true, ips: ['127.0.0.1'], agentVersion: '1.5.3', memTotalMb: 32768, memAvailableMb: 16384 }],
    events: [], configs: [], policy: null, tailscale: { state: 'Missing' },
  });
  if (url === '/api/vibelink/action') {
    const action = JSON.parse(String(init?.body || '{}'));
    if (action.action === 'hub-pair') return Response.json({ id: 'pr_123456', link: 'vibelink://pair?code=fixture-only', expiresAt: Date.now() + 600000, host: 'fixture-desktop', urls: [] });
    if (action.action === 'hub-pair-status') return Response.json({ status: 'pending' });
    return Response.json({ error: 'Agent unavailable' }, { status: 503 });
  }
  return nativeFetch(input, init);
};
mountPage({ render: () => <div className="h-screen"><VibeLinkControlCenter embedded fullHeight /></div> });
