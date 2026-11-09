import assert from 'node:assert/strict';
export default {
 name:'vibelink-admin',entry:'./admin-fixture.tsx',viewport:{width:1440,height:900},
 real:'Admin panel, account capability gate, invite masking, measured and estimated usage, request audit and CAS quota form',
 notCovered:'Real Main/Hub permissions, live invitations and quota writes. All 10 seats and IPC responses are fixtures; no real admin actions.',
 cases:{default:{about:'Ten prepared seats, masked invites and measured usage'},denied:{about:'Main capability denied; no data is fetched'},conflict:{about:'Stale quota revision requires explicit refresh'},unknown:{about:'Unconfirmed write is never automatically retried'}},
 async ready(page){await page.getByRole('heading',{level:1}).waitFor();},
 async check(page,ui){
  if(ui.case==='denied'){await page.getByText(await ui.t('vibelinkAdmin.denied'),{exact:true}).waitFor();assert.equal(await page.getByText('Fixture phone 1',{exact:true}).count(),0);await ui.capture('admin-denied');return;}
  await page.getByText('Fixture phone 1',{exact:true}).waitFor();
  assert.equal(await page.getByText('fixture-only-seat-1-not-valid',{exact:true}).count(),0);
  await ui.capture('admin-seats');
  if(ui.case==='default'){
   await page.getByRole('button',{name:await ui.t('vibelinkAdmin.show'),exact:true}).first().click();
   assert.equal(await page.getByText('fixture-only-seat-1-not-valid',{exact:true}).isVisible(),true);
   await page.getByRole('button',{name:await ui.t('vibelinkAdmin.hide'),exact:true}).click();
   await page.getByRole('heading',{name:await ui.t('vibelinkAdmin.usage'),exact:true}).scrollIntoViewIfNeeded();
   await ui.capture('admin-usage');
   await page.getByRole('heading',{name:await ui.t('vibelinkAdmin.recent'),exact:true}).scrollIntoViewIfNeeded();
   await ui.capture('admin-recent');
  }
  await page.getByRole('button',{name:(await ui.t('vibelinkAdmin.edit'))+' beta_01',exact:true}).click();
  await page.getByLabel(await ui.t('vibelinkAdmin.dailyTokens'),{exact:true}).fill('1200000');
  await ui.capture('admin-limits');
  await page.getByRole('button',{name:await ui.t('vibelinkAdmin.save'),exact:true}).click();
  await page.getByText(await ui.t(ui.case==='conflict'?'vibelinkAdmin.conflict':ui.case==='unknown'?'vibelinkAdmin.unknown':'vibelinkAdmin.saved'),{exact:true}).waitFor();
  if(ui.case==='conflict'||ui.case==='unknown')assert.equal(await page.getByRole('button',{name:(await ui.t('vibelinkAdmin.edit'))+' beta_01',exact:true}).isDisabled(),true);
  await ui.capture('admin-save-receipt');
 }
};
