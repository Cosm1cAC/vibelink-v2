import assert from 'node:assert/strict';
export default {
  name: 'vibelink-login', entry: './login-fixture.tsx',
  viewport: { width: 1100, height: 800 },
  real: 'Unified login component, semantic themes, real locale resources, invite form and QR rendering',
  notCovered: 'Main safeStorage, real authentication, polling and owner changes; verified in native Electron separately',
  cases: {
    default: { about: 'Fresh signed-out profile, invite form and explicit local history action' },
    setup: { about: 'Explicit first-phone setup and cancellation using a deferred fake local verification' },
    legacy: { about: 'Old dev desktop_login QR is hidden; only the unified scan entry remains' },
    scanned: { about: 'Real QR rendering and waiting for phone confirmation' },
  },
  async ready(page) { await page.locator('h1').waitFor(); },
  async check(page, ui) {
    if(ui.case==='scanned')await page.getByRole('combobox').selectOption('beta');
    await ui.capture('login');
    const setup=page.getByTestId('vibelink-scan-login');
    if(ui.case==='scanned'){
      await page.getByAltText(await ui.t('vibelinkAuth.qrAlt')).waitFor();
      assert.equal(await setup.count(),0);
      assert.equal(await page.getByAltText(await ui.t('vibelinkAuth.qrAlt')).count(),1);
      await ui.capture('beta-single-qr');
    }else{
      assert.equal(await setup.isVisible(),true);
      assert.equal(await page.getByAltText(await ui.t('vibelinkAuth.qrAlt')).count(),0);
      assert.equal(await page.getByTestId('vibelink-first-phone').count(),0);
    }
    if(ui.case==='setup'){
      await setup.click();
      await page.getByTestId('vibelink-cancel-first-phone').waitFor();
      await ui.capture('phone-setup-pending');
      await page.getByTestId('vibelink-cancel-first-phone').click();
      await page.getByTestId('vibelink-cancel-first-phone').waitFor({state:'hidden'});
      assert.equal(await setup.isEnabled(),true);
      await ui.capture('phone-setup-cancelled');
    }
  },
};
