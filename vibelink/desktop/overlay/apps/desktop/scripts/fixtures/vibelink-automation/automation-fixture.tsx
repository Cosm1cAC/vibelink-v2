import type {AutomationInput,AutomationResult,AutomationView} from '../../../src/shared/vibelinkAutomation';
import type {HubWorkInput,HubWorkResult} from '../../../src/shared/vibelinkWork';
import {mountPage} from '../../ui-page/harness';
import {HubAutomationPanel} from '../../../src/renderer/features/vibelink/HubAutomationPanel';
const session={userId:'beta_01',label:'Fixture account',tenantId:'beta_01',edition:'beta' as const,sessionId:'fixture-observe',expiresAt:9999999999999,issuer:'hub_aaaaaaaaaaaaaaaaaaaaaaaa',hubId:'hub_aaaaaaaaaaaaaaaaaaaaaaaa',permissions:['client' as const]};
let bridge:{automation(input:AutomationInput):Promise<AutomationResult>;work(input:HubWorkInput):Promise<HubWorkResult>};
mountPage({host:ctx=>{
 let view:AutomationView={groupId:'g1',policy:{mode:ctx.case==='off'||ctx.case==='unknown'?'off':'observe',revision:4},observer:{coverage:'latest_work_snapshot',baselineAt:Date.now()-60000,lastScanAt:Date.now()-2000,error:ctx.case==='capacity'?'capacity':'',decisionLimit:50000,ruleLimit:256,eventLimit:10000},rules:[{id:'r1',label:'Previous review',statuses:['failed'],version:1,createdAt:Date.now()-60000,revokedAt:Date.now()-30000},{id:'r1',label:'Review failures',statuses:['failed','needs_input'],version:2,createdAt:Date.now()-30000,revokedAt:0}],decisions:[{id:'d3',eventId:'e3',workId:'work_003',workRevision:12,status:'needs_input',ruleId:'r1',ruleVersion:2,at:Date.now()-2000,outcome:ctx.case==='off'?'suppressed':'suggested',reason:ctx.case==='off'?'off':'matched'},{id:'d2',eventId:'e2',workId:'work_002',workRevision:7,status:'failed',ruleId:'r1',ruleVersion:2,at:Date.now()-10000,outcome:ctx.case==='off'?'suppressed':'suggested',reason:ctx.case==='off'?'off':'matched'},{id:'d1',eventId:'e1',workId:'work_001',workRevision:4,status:'failed',ruleId:'r1',ruleVersion:1,at:Date.now()-20000,outcome:'suppressed',reason:'revoked'}]};
 bridge={
  work:async input=>{if(input.action!=='groups')throw Error('Fixture forbids dispatching Work');return {success:true,data:{kind:'groups',groups:[{id:'g1',name:'Delivery team',description:'Fixture group',botIds:[]}]}};},
  automation:async(input:AutomationInput):Promise<AutomationResult>=>{
   if(input.action==='snapshot')return {success:true,data:view};
   const revision=view.policy.revision+1;
   if(input.action==='setMode'){view={...view,policy:{mode:input.mode,revision}};if(ctx.case==='unknown')return {success:false,error:{code:'OUTCOME_UNKNOWN',message:'Fixture unconfirmed write'}};}
   if(input.action==='saveRule'){
    const version=Math.max(0,...view.rules.filter(rule=>rule.id===input.ruleId).map(rule=>rule.version))+1;
    view={...view,policy:{...view.policy,revision},rules:[...view.rules.map(rule=>rule.id===input.ruleId?{...rule,revokedAt:Date.now()}:rule),{id:input.ruleId,label:input.label,statuses:input.statuses,version,createdAt:Date.now(),revokedAt:0}]};
   }
   if(input.action==='revokeRule')view={...view,policy:{...view.policy,revision},rules:view.rules.map(rule=>rule.id===input.ruleId?{...rule,revokedAt:Date.now()}:rule)};
   return {success:true,data:view};
  }
 };return {};
},render:()=> <main className="h-screen overflow-auto bg-[var(--surface)] p-6 text-[var(--text-primary)]"><div className="mx-auto max-w-[1280px]"><HubAutomationPanel session={session} bridge={bridge}/></div></main>});
