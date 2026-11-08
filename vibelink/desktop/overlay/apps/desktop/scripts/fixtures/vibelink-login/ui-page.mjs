export default {
  name: 'vibelink-login', entry: './login-fixture.tsx',
  viewport: { width: 1100, height: 800 },
  real: 'Unified login component, semantic themes, real locale resources, invite form and QR rendering',
  notCovered: 'Main safeStorage, real authentication, polling and owner changes; verified in native Electron separately',
  cases: {
    default: { about: 'Fresh signed-out profile, invite form and explicit local history action' },
    scanned: { about: 'Real QR rendering and waiting for phone confirmation' },
  },
  async ready(page) { await page.locator('h1').waitFor(); },
  async check(page, ui) { await ui.capture('login'); },
};
