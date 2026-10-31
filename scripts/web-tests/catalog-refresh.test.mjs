import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createCatalogRefresh} from '../../cloudflare-site/src/catalog-refresh.mjs';
// قارن الشيفرة التنفيذية مع تجاهل اختلاف التعليقات فقط.
const stripComments=value=>value.replace(/\/\*[\s\S]*?\*\//g,m=>m.replace(/[^\n]/g,"")).replace(/(^|\n)[ \t]*\/\/[^\n]*/g,"$1").replace(/[ \t]+\/\/[^\n]*/g,"").replace(/\n[ \t]*\n+/g,"\n").trim();
const env={PROBE_KEY:'test-probe-00000000000000000000',RESPONSE_MASK:'test-format-mask',CATALOG_SYNC_KEY:'test-sync-000000000000000000000000'};
const request=(action='start',key=env.PROBE_KEY)=>new Request('https://offline.invalid/api/internal/catalog-update',{method:'POST',body:new URLSearchParams({probe_key:key,action})});
function context(){const data=new Map();let alarm=null;return {data,get alarm(){return alarm;},storage:{async get(k){return structuredClone(data.get(k));},async put(k,v){data.set(k,structuredClone(v));},async setAlarm(t){alarm=t;},async deleteAlarm(){alarm=null;}}};}
function job(check,ctx=context(),settings=env){const Class=createCatalogRefresh(class{},check);return {instance:new Class(ctx,settings),ctx,Class};}
test('GET displays activation, no checks, and wrong key cannot start',async()=>{
 let calls=0;const {instance,ctx}=job({fetch(){calls++;}});
 assert.equal((await instance.fetch(new Request('https://offline.invalid/api/internal/catalog-update'))).status,200);
 assert.equal((await instance.fetch(request('start','wrong'))).status,401);assert.equal(calls,0);assert.equal(ctx.alarm,null);
});
test('missing required config does not arm alarm or fetch',async()=>{
 for(const field of ['PROBE_KEY','RESPONSE_MASK','CATALOG_SYNC_KEY']){
  const {instance,ctx}=job({fetch(){throw Error('must not run');}},context(),{...env,[field]:undefined});
  assert.equal((await instance.fetch(request())).status,503);assert.equal(ctx.alarm,null);
 }
});
test('start saves immediately and arms independent persistent next run',async()=>{
 let calls=0;const {instance,ctx}=job({async fetch(request,settings,save){calls++;assert.equal(new URLSearchParams(await request.text()).get('publish'),'yes');return Response.json({published:true,sourceHTTP:200,publishResult:{changed:1,matched:20}});}});
 const start=Date.now();const result=await(await instance.fetch(request())).json();
 assert.equal(result.enabled,true);assert.equal(result.lastSaved,true);assert.equal(result.changed,1);assert.equal(calls,1);assert.ok(ctx.alarm>=start+10000);assert.equal(result.intervalMs,10000);assert.ok(result.lastSuccess);
 assert.ok(!JSON.stringify(result).includes(env.PROBE_KEY));
 await instance.alarm();assert.equal(calls,2);assert.equal(ctx.data.get('catalog:auto').enabled,true);
});
test('instance recreated from persisted state keeps refreshing with no phone request',async()=>{
 let calls=0;const {instance,ctx,Class}=job({async fetch(){calls++;return Response.json({published:true});}});
 await instance.fetch(request());const next=new Class(ctx,env);await next.alarm();assert.equal(calls,2);assert.ok(ctx.alarm>Date.now());
});
test('failure keeps schedule, retries later and clears error on success',async()=>{
 let fail=true;const {instance,ctx}=job({async fetch(){if(fail)throw Error('private');return Response.json({published:true});}});
 await instance.fetch(request());assert.equal(ctx.data.get('catalog:auto').lastSaved,false);assert.ok(ctx.alarm);
 fail=false;await instance.alarm();assert.equal(ctx.data.get('catalog:auto').lastSaved,true);assert.equal(ctx.data.get('catalog:auto').error,null);
});
test('stop removes alarm and subsequent alarms cannot fetch',async()=>{
 let calls=0;const {instance,ctx}=job({async fetch(){calls++;return Response.json({published:true});}});
 await instance.fetch(request());await instance.fetch(request('stop'));await instance.alarm();assert.equal(calls,1);assert.equal(ctx.alarm,null);
 const result=await(await instance.fetch(request('status'))).json();assert.equal(result.enabled,false);assert.equal(result.nextRun,null);
});
test('concurrent alarm calls share one in-flight update',async()=>{
 let calls=0,release;const gate=new Promise(r=>release=r);const ctx=context();ctx.data.set('catalog:auto',{enabled:true});
 const {instance}=job({async fetch(){calls++;await gate;return Response.json({published:true});}},ctx);
 const a=instance.alarm(),b=instance.alarm();await new Promise(r=>setTimeout(r,1));assert.equal(calls,1);release();await Promise.all([a,b]);
});
test('stop during source request suppresses pending save callback',async()=>{
 let ready,release,saves=0;const opened=new Promise(r=>ready=r),gate=new Promise(r=>release=r);const ctx=context();ctx.data.set('catalog:auto',{enabled:true});
 const {instance}=job({async fetch(req,e,save){ready();await gate;const r=await save({});assert.equal((await r.json()).ignored,true);return Response.json({published:false});}},ctx,{...env,SIGNALING:{get(){saves++;throw Error('must not save');}}});
 const pending=instance.alarm();await opened;await instance.fetch(request('stop'));release();await pending;assert.equal(saves,0);assert.equal(ctx.alarm,null);
});
test('save uses existing global room binding with no public Worker fetch',async()=>{
 let saved=false;const {instance}=job({async fetch(req,e,save){const r=await save({entries:[]});return Response.json({published:(await r.json()).ok});}},context(),{...env,SIGNALING:{idFromName(n){assert.equal(n,'global-room');return n;},get(){return{async fetch(req){saved=true;assert.equal(new URL(req.url).pathname,'/api/internal/football-catalog-sync');assert.equal(req.headers.get('Authorization'),'Bearer '+env.CATALOG_SYNC_KEY);return Response.json({ok:true});}};}}});
 await instance.fetch(request());assert.equal(saved,true);
});
test('production bundle and binding migration match canonical implementation',async()=>{
 const module=await readFile(new URL('../../cloudflare-site/src/catalog-refresh.mjs',import.meta.url),'utf8');
 const worker=await readFile(new URL('../../cloudflare-site/src/worker.js',import.meta.url),'utf8');
 const config=await readFile(new URL('../../cloudflare-site/wrangler.toml',import.meta.url),'utf8');
 const expected='// BEGIN مجدول تحديث الكتالوج\n'+module.replace('export function ','function ')+'\nexport const CatalogRefresh = createCatalogRefresh(DurableObject,mainCatalogCheck);';
 assert.ok(stripComments(worker).includes(stripComments(expected)));
 assert.match(config,/name = "CATALOG_REFRESH"\nclass_name = "CatalogRefresh"/);assert.match(config,/new_sqlite_classes = \["CatalogRefresh"\]/);
});
