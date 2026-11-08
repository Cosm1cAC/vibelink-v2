export default {
  name: 'vibelink-work',
  entry: './work-fixture.tsx',
  viewport: { width: 1200, height: 860 },
  real: 'Hub work list, create-group form, detail, readonly management and cancellation states using actual components and locale resources',
  notCovered: 'Main persistence and actual model execution; no upstream calls in this fixture',
  cases: {
    default: { about: 'Shared task details and controls' },
    empty: { about: 'Create first group without model execution' },
    readonly: { about: 'Another principal cannot manage work' },
    unknown: { about: 'Unknown process termination is explicit' },
  },
  async ready(page) {
    await page.locator('h1').waitFor();
    if (await page.locator('select').count())
      await page.getByRole('button', { name: /Compare desktop/ }).waitFor();
  },
  async check(page, ui) {
    if (await page.locator('button').filter({ hasText: 'Compare desktop' }).count())
      await page.locator('button').filter({ hasText: 'Compare desktop' }).click();
    await ui.capture('workspace');
  },
};
