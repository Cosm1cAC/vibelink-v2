export default {
  name: 'vibelink-notifications',
  entry: './notifications-fixture.tsx',
  viewport: { width: 1200, height: 860 },
  real: 'Actual notification settings and receipts component using real translated resources.',
  notCovered:
    'OS popup delivery, Hub requests or credentials. Bridge is inert; no notifications are shown.',
  cases: {
    default: { about: 'Default disabled, preview off' },
    unknown: { about: 'Unconfirmed delivery requires explicit reminder resend' },
    delivered: { about: 'System acceptance is distinct from task read/completion' },
    unavailable: { about: 'OS registration capability is displayed read-only; never auto-enabled' },
  },
  async ready(page) {
    await page.locator('summary').waitFor();
  },
  async check(page, ui) {
    await page.locator('summary').click();
    await page.locator('input').first().waitFor();
    await ui.capture('notifications');
  },
};
