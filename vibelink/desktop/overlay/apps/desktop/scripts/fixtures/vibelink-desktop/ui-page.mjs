import assert from 'node:assert/strict';
export default {
  name: 'vibelink-desktop', entry: './desktop-fixture.tsx',
  viewport: { width: 1280, height: 800 },
  real: 'VibeLink device workspace, local-service response rendering, embedded navigation behavior and failed action message',
  notCovered: 'Cindy IPC, database, native window, actual Hub commands and model inference; tested separately',
  cases: { default: { about: 'Connected local Hub, one Agent, rejected command remains an error' } },
  async ready(page) { await page.getByText('● hub 在线', { exact: true }).waitFor(); },
  async check(page, ui) {
    await page.keyboard.press('Control+Shift+V');
    assert.equal(await page.getByText('● hub 在线', { exact: true }).isVisible(), true);
    await page.getByRole('button', { name: '清系统代理', exact: true }).click();
    await page.getByText('操作失败: Agent unavailable', { exact: true }).waitFor();
    await ui.capture('device-workspace');
    await page.getByRole('button', { name: /连接手机/ }).click();
    await page.getByAltText('vibelink 配对二维码').waitFor();
    assert.equal(await page.getByAltText('vibelink 配对二维码').isVisible(), true);
    await ui.capture('pair-phone');
  },
};
