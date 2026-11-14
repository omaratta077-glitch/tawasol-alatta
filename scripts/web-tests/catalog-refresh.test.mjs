import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createCatalogRefresh} from '../../cloudflare-site/src/catalog-refresh.mjs';

const env={PROBE_KEY:'test-probe-00000000000000000000',RESPONSE_MASK:'test-format-mask',CATALOG_SYNC_KEY:'test-sync-000000000000000000000000',CLOUD_CATALOG_MANUAL_ENABLED:'yes'};
const request=(action='refresh',key=env.PROBE_KEY)=>new Request('https://offline.invalid/api/internal/catalog-update',{method:'POST',body:new URLSearchParams({probe_key:key,action})});
function context(){const data=new Map();let alarm=null;return {data,get alarm(){return alarm;},storage:{async get(k){return structuredClone(data.get(k));},async put(k,v){data.set(k,structuredClone(v));},async setAlarm(t){alarm=t;},async deleteAlarm(){alarm=null;}}};}
function job(check,ctx=context(),settings=env){const Class=createCatalogRefresh(class{},check);return {instance:new Class(ctx,settings),ctx,Class};}

test('GET shows schedule controls and wrong key cannot refresh',async()=>{
 let calls=0;const {instance,ctx}=job({fetch(){calls++;}});
 const response=await instance.fetch(new Request('https://offline.invalid/api/internal/catalog-update'));
 const html=await response.text();
 assert.equal(response.status,200);assert.match(html,/التحديث التلقائي للقنوات/);assert.match(html,/value="start"/);
 assert.equal((await instance.fetch(request('refresh','wrong'))).status,401);assert.equal(calls,0);assert.equal(ctx.alarm,null);
});
test('missing configuration rejects one-shot refresh without touching the last saved channels',async()=>{
 for(const field of ['PROBE_KEY','RESPONSE_MASK','CATALOG_SYNC_KEY']){
  const {instance,ctx}=job({fetch(){throw Error('unexpected upstream');}},context(),{...env,[field]:undefined});
  assert.equal((await instance.fetch(request())).status,503);assert.equal(ctx.alarm,null);
 }
});
test('legacy Cloudflare channel writer is disabled in production by default',async()=>{
 let calls=0;const settings={...env};delete settings.CLOUD_CATALOG_MANUAL_ENABLED;
 const {instance,ctx}=job({fetch(){calls++;throw Error('never called');}},context(),settings);
 const response=await instance.fetch(request('refresh'));
 const status=await response.json();
 assert.equal(response.status,410);
 assert.equal(status.error,'SOURCE_SYNC_PAUSED_USE_PHONE');
 assert.equal(status.enabled,false);
 assert.equal(calls,0);
 assert.equal(ctx.alarm,null);
});
test('an old automatic alarm is cancelled and never fetches or saves',async()=>{
 let calls=0;const ctx=context();
 ctx.data.set('catalog:auto',{enabled:true,nextRun:Date.now()+10000,intervalMs:10000,lastSaved:true,matched:20});
 await ctx.storage.setAlarm(Date.now()+10000);
 const {instance}=job({fetch(){calls++;}},ctx);
 await instance.alarm();
 const status=await(await instance.fetch(request('status'))).json();
 assert.equal(calls,0);assert.equal(ctx.alarm,null);
 assert.equal(status.manualOnly,true);assert.equal(status.enabled,false);assert.equal(status.nextRun,null);
 assert.equal(status.lastSaved,true);assert.equal(status.matched,20);
});
test('GET and status do not start background updates; status cancels legacy alarm',async()=>{
 let calls=0;const ctx=context();ctx.data.set('catalog:auto',{enabled:true,nextRun:42});
 await ctx.storage.setAlarm(42);
 const {instance}=job({fetch(){calls++;}},ctx);
 await instance.fetch(new Request('https://offline.invalid/api/internal/catalog-update'));
 assert.equal(calls,0);
 const status=await(await instance.fetch(request('status'))).json();
 assert.equal(calls,0);assert.equal(status.enabled,false);assert.equal(ctx.alarm,null);
});
test('an owner refresh fetches exactly once, saves a report, and never schedules the next run',async()=>{
 let calls=0;const {instance,ctx}=job({async fetch(req,e,save){
  calls++;
  const form=new URLSearchParams(await req.text());
  assert.equal(form.get('publish'),'yes');assert.equal(form.has('rebaseline'),false);
  return Response.json({published:true,sourceHTTP:200,publishResult:{changed:1,matched:20}});
 }});
 const result=await(await instance.fetch(request())).json();
 assert.equal(result.ok,true);assert.equal(result.manualOnly,true);assert.equal(result.enabled,false);
 assert.equal(result.lastSaved,true);assert.equal(result.changed,1);assert.equal(result.matched,20);
 assert.ok(result.lastSuccess);assert.equal(result.nextRun,null);assert.equal(ctx.alarm,null);
 assert.equal(calls,1);assert.ok(!JSON.stringify(result).includes(env.PROBE_KEY));
 await instance.alarm();assert.equal(calls,1);
});
test('a second explicit click refreshes again; no phone and no scheduled polling required',async()=>{
 let calls=0;const {instance,ctx}=job({async fetch(){calls++;return Response.json({published:true,publishResult:{changed:0,matched:20}});}});
 await instance.fetch(request());await instance.fetch(request());
 assert.equal(calls,2);assert.equal(ctx.alarm,null);
});
test('legacy start action performs only one refresh, never enables automation',async()=>{
 let calls=0;const {instance,ctx}=job({async fetch(){calls++;return Response.json({published:true});}});
 const result=await(await instance.fetch(request('start'))).json();
 assert.equal(calls,1);assert.equal(result.enabled,false);assert.equal(result.nextRun,null);
 await instance.alarm();assert.equal(calls,1);assert.equal(ctx.alarm,null);
});
test('scheduled 403 exposes limited source attempts but never overwrites saved channels',async()=>{
 const envBlocked={...env,CLOUD_CATALOG_AUTO_ENABLED:'yes'};
 const {instance,ctx}=job({async fetch(){return Response.json({published:false,sourceHTTP:403,
  failureCode:'CATEGORY_HTTP_ERROR',sourceAttempts:[
   {sourceURL:'https://def111.ycnapi.com/api/categories',state:'HTTP_ERROR',sourceHTTP:403},
   {sourceURL:'http://def111.ycnapi.com/api/categories',state:'HTTP_ERROR',sourceHTTP:403},
   {sourceURL:'https://def001.ycnapi.com/api/categories',state:'NETWORK_ERROR',sourceHTTP:null},
   {sourceURL:'https://unrelated.invalid',state:'LIST',sourceHTTP:200}
  ]});}},context(),envBlocked);
 const result=await(await instance.fetch(request('scheduled'))).json();
 assert.equal(result.lastSaved,false);
 assert.equal(result.error,'CATEGORY_HTTP_ERROR');
 assert.equal(result.sourceAttempts.length,3);
 assert.equal(result.sourceAttempts[0].sourceHTTP,403);
 assert.equal(result.sourceAttempts[2].sourceHTTP,null);
 assert.equal(ctx.alarm,null);
 assert.ok(!JSON.stringify(result).includes(env.PROBE_KEY));
});

test('failed manual refresh preserves the previous catalog and does not retry in background',async()=>{
 let calls=0;const {instance,ctx}=job({async fetch(){calls++;return Response.json({published:false,sourceHTTP:404,publishResult:{errorCode:'NO_MATCHING_CHANNELS'}});}});
 const result=await(await instance.fetch(request())).json();
 assert.equal(result.enabled,false);assert.equal(result.lastSaved,false);
 assert.equal(result.error,'NO_MATCHING_CHANNELS');assert.equal(result.lastSourceHTTP,404);assert.equal(ctx.alarm,null);
 await instance.alarm();assert.equal(calls,1);
});
test('manual rebaseline remains guarded but never starts periodic updates',async()=>{
 let calls=0;const {instance,ctx}=job({async fetch(req){
  calls++;const form=new URLSearchParams(await req.text());assert.equal(form.get('rebaseline'),'yes');
  return Response.json({published:true,publishResult:{matched:20,changed:20}});
 }});
 const result=await(await instance.fetch(request('rebaseline'))).json();
 assert.equal(calls,1);assert.equal(result.enabled,false);assert.equal(result.changed,20);
 assert.ok(result.lastRebaseline);assert.equal(ctx.alarm,null);
});
test('concurrent manual refresh attempts run at most once',async()=>{
 let calls=0,release,entered;
 const wait=new Promise(r=>release=r),started=new Promise(r=>entered=r);
 const {instance,ctx}=job({async fetch(){
  calls++;entered();await wait;return Response.json({published:true});
 }});
 const one=instance.fetch(request());
 await started; // first request holds the lock; do not rely on a scheduling timer
 const second=await instance.fetch(request());
 assert.equal(second.status,409);
 release();
 const first=await one;
 assert.equal(first.status,200);
 assert.equal(calls,1);assert.equal(ctx.alarm,null);
});
test('stop invalidates an in-flight manual refresh before the save callback',async()=>{
 let ready,release,saves=0;const opened=new Promise(r=>ready=r),wait=new Promise(r=>release=r);
 const {instance,ctx}=job({async fetch(req,e,save){
  ready();await wait;
  const response=await save({entries:[]});assert.equal((await response.json()).ignored,true);
  return Response.json({published:false});
 }},context(),{...env,SIGNALING:{get(){saves++;throw Error('no save after stop');}}});
 const pending=instance.fetch(request());await opened;
 const stop=await(await instance.fetch(request('stop'))).json();
 assert.equal(stop.enabled,false);release();await pending;
 assert.equal(saves,0);assert.equal(ctx.alarm,null);
});
test('owner manual refresh writes through existing private global room binding, not external HTTP',async()=>{
 let saved=false;
 const {instance}=job({async fetch(req,e,save){
  const result=await save({entries:[]});return Response.json({published:(await result.json()).ok});
 }},context(),{...env,SIGNALING:{idFromName(n){assert.equal(n,'global-room');return n;},
  get(){return{async fetch(req){saved=true;
   assert.equal(new URL(req.url).pathname,'/api/internal/football-catalog-sync');
   assert.equal(req.headers.get('Authorization'),'Bearer '+env.CATALOG_SYNC_KEY);
   return Response.json({ok:true});}};}}});
 const result=await(await instance.fetch(request())).json();
 assert.equal(saved,true);assert.equal(result.lastSaved,true);
});
test('production worker contains identical Cloudflare Cron implementation and registered object',async()=>{
 const module=await readFile(new URL('../../cloudflare-site/src/catalog-refresh.mjs',import.meta.url),'utf8');
 const worker=await readFile(new URL('../../cloudflare-site/src/worker.js',import.meta.url),'utf8');
 const config=await readFile(new URL('../../cloudflare-site/wrangler.toml',import.meta.url),'utf8');
 const newline=String.fromCharCode(10);
 const expected='// BEGIN مجدول تحديث الكتالوج'+newline+module.replace('export function ','function ')+newline+'export const CatalogRefresh = createCatalogRefresh(DurableObject,mainCatalogCheck);';
 assert.ok(worker.includes(expected));
 assert.ok(config.includes('name = "CATALOG_REFRESH"'+newline+'class_name = "CatalogRefresh"'));
 assert.ok(config.includes('new_sqlite_classes = ["CatalogRefresh"]'));
 assert.ok(!module.includes('setAlarm('));
 assert.ok(worker.includes('async scheduled(controller,env,ctx)'));
 assert.ok(worker.includes("action:'scheduled'"));
 assert.ok(config.includes('crons = ["*/5 * * * *"]'));
 assert.ok(config.includes('CLOUD_CATALOG_AUTO_ENABLED = "yes"'));
});

test('Cloudflare cron refresh does not touch saved channels without configuration',async()=>{
 let calls=0;
 const auto={...env,CLOUD_CATALOG_AUTO_ENABLED:'yes',RESPONSE_MASK:undefined};
 const {instance,ctx}=job({async fetch(){calls++;throw Error('unexpected network');}},context(),auto);
 const out=await instance.fetch(request('scheduled'));
 assert.equal(out.status,503);
 assert.equal(calls,0);
 assert.equal(ctx.alarm,null);
});
test('Cloudflare scheduled refresh runs on valid settings and reports auto enabled',async()=>{
 let calls=0;
 const auto={...env,CLOUD_CATALOG_AUTO_ENABLED:'yes'};
 const {instance,ctx}=job({async fetch(){calls++;return Response.json({published:true,sourceHTTP:200,publishResult:{changed:2,matched:20}});}},context(),auto);
 const result=await(await instance.fetch(request('scheduled'))).json();
 assert.equal(result.enabled,true);
 assert.equal(result.lastSaved,true);
 assert.equal(result.matched,20);
 assert.equal(calls,1);
 assert.equal(result.configuration.responseMaskConfigured,true);
 assert.equal(ctx.alarm,null);
});
test('owner stop pauses Cron ticks until explicit owner start',async()=>{
 let calls=0;
 const auto={...env,CLOUD_CATALOG_AUTO_ENABLED:'yes'};
 const {instance,ctx}=job({async fetch(){calls++;return Response.json({published:true});}},context(),auto);
 const before=await(await instance.fetch(request('status'))).json();
 assert.equal(before.enabled,true);
 const paused=await(await instance.fetch(request('stop'))).json();
 assert.equal(paused.enabled,false);
 const missed=await(await instance.fetch(request('scheduled'))).json();
 assert.equal(missed.skipped,true);
 assert.equal(calls,0);
 const resumed=await(await instance.fetch(request('start'))).json();
 assert.equal(resumed.enabled,true);
 assert.equal(calls,1);
 await instance.fetch(request('scheduled'));
 assert.equal(calls,2);
 assert.equal(ctx.alarm,null);
});
test('scheduled action still requires owner secret and auto flag',async()=>{
 const {instance}=job({async fetch(){throw Error('must not run');}},context(),{...env,CLOUD_CATALOG_AUTO_ENABLED:'yes'});
 assert.equal((await instance.fetch(request('scheduled','wrong'))).status,401);
 const legacy=job({async fetch(){throw Error('must not run');}});
 assert.equal((await legacy.instance.fetch(request('scheduled'))).status,410);
});
