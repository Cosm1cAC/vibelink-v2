import assert from 'node:assert/strict';
export default {
 name:'vibelink-automation',entry:'./automation-fixture.tsx',viewport:{width:1360,height:1000},
 real:'Scoped group observe/off panel, versioned rule form, CAS mode save, unknown-write lock and observer audit projection',
 notCovered:'Live Hub, real rules, observation worker, model calls, Work dispatch or notifications. All API responses and clicks use isolated fixtures.',
 cases:{default:{about:'Observe mode with current and prior rule versions'},off:{about:'Server-confirmed off mode and suppressed decisions'},unknown:{about:'Unconfirmed mode save locks writes until refresh'},capacity:{about:'Audit recording paused for capacity without execution claims'}},
 async ready(page){await page.getByRole('button',{name:await page.evaluate(()=>window.__uiPage?.ctx?.locale==='zh-CN'?'主动运行 · 仅观察':'Proactive operation · observe only')}).waitFor();},
 async check(page,ui){
  await ui.capture('automation-collapsed');
  await page.getByRole('button',{name:await ui.t('vibelinkAutomation.title'),exact:true}).click();
  await page.getByText('Review failures',{exact:true}).waitFor();
  const mode=page.getByLabel(await ui.t('vibelinkAutomation.mode'),{exact:true});
  assert.equal(await mode.inputValue(),ui.case==='off'||ui.case==='unknown'?'off':'observe');
  assert.equal(await mode.locator('option[value="auto"]').count(),0);
  await ui.capture('automation-snapshot');
  if(ui.case==='default'){
   await page.getByRole('button',{name:await ui.t('vibelinkAutomation.newRule'),exact:true}).click();
   assert.equal(await page.getByLabel(await ui.t('vibelinkAutomation.ruleLabel'),{exact:true}).inputValue(),'');
   await page.getByLabel(await ui.t('vibelinkAutomation.ruleLabel'),{exact:true}).fill('Completion review');
   await page.getByLabel(await ui.t('vibelinkAutomation.completed'),{exact:true}).check();
   await ui.capture('automation-rule-form');
   await page.getByRole('button',{name:await ui.t('vibelinkAutomation.saveRule'),exact:true}).click();
   await page.getByText('Completion review',{exact:true}).waitFor();
   await ui.capture('automation-rule-saved');
  }
  if(ui.case==='unknown'){
   await mode.selectOption('observe');
   await page.getByRole('button',{name:await ui.t('vibelinkAutomation.saveMode'),exact:true}).click();
   await page.getByText(await ui.t('vibelinkAutomation.unknown'),{exact:true}).waitFor();
   assert.equal(await mode.isDisabled(),true);
   assert.equal(await page.getByRole('button',{name:await ui.t('vibelinkAutomation.newRule'),exact:true}).isDisabled(),true);
   await ui.capture('automation-write-unknown');
   await page.getByRole('button',{name:await ui.t('vibelinkAutomation.refresh'),exact:true}).click();
   await page.getByText((await ui.t('vibelinkAutomation.policyVersion'))+' v5',{exact:true}).waitFor();
   assert.equal(await mode.isDisabled(),false);
   await ui.capture('automation-explicit-refresh');
  }
  if(ui.case==='capacity')await page.getByText(await ui.t('vibelinkAutomation.capacityPaused'),{exact:true}).waitFor();
 }
};
