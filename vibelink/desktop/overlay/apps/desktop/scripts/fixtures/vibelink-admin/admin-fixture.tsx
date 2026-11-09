import type {AdminInput,AdminResult} from '../../../src/shared/vibelinkAdmin';
import {mountPage} from '../../ui-page/harness';
import {HubAdminPanel} from '../../../src/renderer/features/vibelink/HubAdminPanel';
import {utc8Day} from '../../../src/renderer/features/vibelink/adminPresentation';
const limits={dailyRequests:300,dailyTokens:1500000,dailyInputBytes:12000000,concurrentRequests:2,requestsPerMinute:30};
const owner={userId:'owner',label:'Fixture owner',tenantId:null,edition:'dev' as const,sessionId:'fixture-admin',expiresAt:9999999999999,issuer:'hub_aaaaaaaaaaaaaaaaaaaaaaaa',hubId:'hub_aaaaaaaaaaaaaaaaaaaaaaaa',permissions:['client' as const]};
const seats=Array.from({length:10},(_,i)=>({tenantId:`beta_${String(i+1).padStart(2,'0')}`,label:`Seat ${String(i+1).padStart(2,'0')}`,inviteCode:`fixture-only-seat-${i+1}-not-valid`,activatedAt:i<3?Date.parse('2026-11-08T12:00:00Z'):0,lastDevice:i<3?`Fixture phone ${i+1}`:'',limits,limitsRevision:4}));
let bridge:{admin(input:AdminInput):Promise<AdminResult>};
mountPage({
 host:ctx=>{
  bridge={admin:async(input:AdminInput):Promise<AdminResult>=>{
   if(input.action==='capability')return {success:true,data:{kind:'capability',allowed:ctx.case!=='denied',reason:''}};
   if(ctx.case==='denied')throw Error('A denied fixture must never request administrative data');
   if(input.action==='snapshot')return {success:true,data:{kind:'snapshot',day:input.day,seats,users:seats.map((seat,i)=>({userId:seat.tenantId,label:seat.label,day:input.day,model:'auto/deepseek-v4.1-flash',usedRequests:i<3?280-i*80:0,successfulRequests:i<3?275-i*80:0,failedRequests:i<3?3:0,interruptedRequests:i<3?2:0,inputTokens:i<3?240000:0,outputTokens:i<3?12000:0,cachedTokens:i<3?180000:0,totalTokens:i<3?252000:0,estimatedTokens:i===0?700:0,usageMissingRequests:i===0?1:0,reservedTokens:i===0?900:0,chargedTokens:i<3?253600:0,inputBytes:i<3?1400000:0,limits,limitsRevision:4,warning:i===0?'near_limit':''}))}};
   if(input.action==='requests')return {success:true,data:{kind:'requests',requests:[{requestId:'fixture-request',userId:'beta_01',workId:'fixture-work',day:utc8Day(),model:'auto/deepseek-v4.1-flash',protocol:'responses',startedAt:Date.now()-20000,finishedAt:Date.now()-18000,durationMs:2000,outcome:'completed',httpStatus:200,tokens:{input:24000,output:1200,cached:18000,total:25200},usageMissing:false,estimatedTokens:0}]}};
   if(ctx.case==='conflict'||ctx.case==='unknown')return {success:false,error:{code:ctx.case==='conflict'?'CONFLICT':'OUTCOME_UNKNOWN',message:'Fixture error',recoverable:true}};
   return {success:true,data:{kind:'limitsSaved',userId:input.userId,limits:input.limits,limitsRevision:5}};
  }};return {};
 },
 render:()=> <main className="h-screen overflow-auto bg-[var(--surface)] p-6 text-[var(--text-primary)]"><div className="mx-auto max-w-[1440px]"><HubAdminPanel session={owner} bridge={bridge}/></div></main>,
});
