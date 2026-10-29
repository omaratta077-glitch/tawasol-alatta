import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {catalogIngest} from '../../cloudflare-site/src/catalog-ingest.mjs';
const source=await readFile(new URL('../../cloudflare-site/src/worker.js',import.meta.url),'utf8');
const moduleText=await readFile(new URL('../../cloudflare-site/src/catalog-check.mjs',import.meta.url),'utf8');
const {default:worker}=await import('data:text/javascript;charset=utf-8,'+encodeURIComponent(source.replace('import { DurableObject } from "cloudflare:workers";','class DurableObject {}')));
const key='test-probe-00000000000000000000',sync='test-sync-000000000000000000000000';
const section='652497361000000001',channel='4973946197000000001';
const form=(extra={})=>new Request('https://owned-app.invalid/api/internal/catalog-check',{method:'POST',body:new URLSearchParams({probe_key:key,publish:'yes',...extra})});
const fixture=()=>({revision:1,channels:[{id:'stable-1',name:'beIN SPORTS 1',streamUrl:'123',format:'ytv',enabled:true}],matches:[{id:'match',channelId:'stable-1'}]});
function context(){
 const data=new Map([['football:catalog',fixture()]]);let saves=0;
 const storage={async get(k){return structuredClone(data.get(k));},async put(k,v){data.set(k,structuredClone(v));},async transaction(fn){const before=structuredClone(data);try{return await fn(this);}catch(e){data.clear();for(const pair of before)data.set(...pair);throw e;}}};
 const env={PROBE_KEY:key,CATALOG_SYNC_KEY:sync,SIGNALING:{idFromName(name){assert.equal(name,'global-room');return name;},get(){return{async fetch(request){saves++;assert.equal(new URL(request.url).pathname,'/api/internal/football-catalog-sync');return catalogIngest(request,storage,env);}};}}};
 return {env,data,get saves(){return saves;}};
}
async function mockSource(fn,{missing=false,bad=false,encoded=false}={}){
 let calls=0;globalThis.fetch=async(url,options)=>{
  calls++;assert.equal(new Headers(options.headers).has('Authorization'),false);
  if(calls===1){assert.equal(url,'https://def001.ycnapi.com/api/categories');return Response.json({data:missing?[]:[{id:section,name:'beIN SPORTS (1080P)'}]},{headers:{t:'1793252988'}});}
  assert.equal(calls,2,'No global HTTP request may save back into the app');
  assert.equal(url,'https://def001.ycnapi.com/api/categories/'+section+'/channels');
  const payload={data:[{id:bad?'0':channel,name:'beIN SPORTS 1'}]};
  if(encoded){
   const bytes=new TextEncoder().encode(JSON.stringify(payload)),mask=new TextEncoder().encode('test-format-mask1793252988');
   const text=btoa(Array.from(bytes,(v,i)=>String.fromCharCode(v^mask[i%mask.length])).join(''));
   return new Response(text,{headers:{t:'1793252988','Content-Type':'application/json'}});
  }
  return Response.json(payload,{headers:{t:'1793252988'}});
 };
 try{return await fn(()=>calls);}finally{globalThis.fetch=async()=>{throw Error('No real network');};}
}
test('page uses its own path and GET never fetches or saves',async()=>{
 const ctx=context();globalThis.fetch=async()=>{throw Error('No source');};
 const response=await worker.fetch(new Request('https://owned-app.invalid/api/internal/catalog-check'),ctx.env);
 assert.equal(response.status,200);assert.match(await response.text(),/action="\/api\/internal\/catalog-check"/);assert.equal(ctx.saves,0);
});
test('wrong or missing probe credentials cannot fetch or save',async()=>{
 const ctx=context();globalThis.fetch=async()=>{throw Error('No source');};
 assert.equal((await worker.fetch(form({probe_key:'wrong'}),ctx.env)).status,401);
 assert.equal((await worker.fetch(form(),{...ctx.env,PROBE_KEY:undefined})).status,503);assert.equal(ctx.saves,0);
});
test('main application saves directly into existing room without Worker HTTP',async()=>mockSource(async calls=>{
 const ctx=context();const result=await(await worker.fetch(form(),ctx.env)).json();
 assert.equal(result.published,true);assert.equal(result.publishResult.status,200);assert.equal(ctx.saves,1);assert.equal(calls(),2);
 assert.equal(ctx.data.get('football:catalog').channels[0].streamUrl,channel);
 assert.deepEqual(ctx.data.get('football:catalog').matches,fixture().matches);
 assert.equal(result.diagnosticVersion,'main-catalog-check-1');assert.ok(!JSON.stringify(result).includes(sync));
}));
test('read-only sections preview cannot write',async()=>mockSource(async calls=>{
 const ctx=context();const result=await(await worker.fetch(form({publish:''}),ctx.env)).json();
 assert.equal(calls(),1);assert.equal(ctx.saves,0);assert.equal(result.published,false);
}));
test('missing section and invalid channel keep last good catalog',async()=>{
 for(const flags of [{missing:true},{bad:true}])await mockSource(async()=>{
  const ctx=context();const result=await(await worker.fetch(form(),ctx.env)).json();
  assert.equal(result.published,false);assert.equal(ctx.saves,0);assert.deepEqual(ctx.data.get('football:catalog'),fixture());
 },flags);
});
test('receiver rejection survives without public fetch fallback',async()=>mockSource(async()=>{
 const ctx=context();ctx.data.set('football:catalog',{...fixture(),channels:[]});
 const result=await(await worker.fetch(form(),ctx.env)).json();
 assert.equal(result.published,false);assert.equal(result.publishResult.status,409);assert.equal(result.publishResult.errorCode,'NO_MATCHING_CHANNELS');
}));
test('missing sync secret blocks publication before source request',async()=>{
 const ctx=context();globalThis.fetch=async()=>{throw Error('No source');};
 assert.equal((await worker.fetch(form(),{...ctx.env,CATALOG_SYNC_KEY:undefined})).status,503);assert.equal(ctx.saves,0);
});
test('production bundle matches standalone module',()=>{
 assert.ok(source.includes('// BEGIN main catalog check\nconst mainCatalogCheck = (() => {\n'+moduleText.replace('export default {','return {')+'\n})();\n// END main catalog check'));
 assert.ok(!moduleText.includes('workers.dev/api/internal/football-catalog-sync'));
});

test('encoded response uses only configured private format setting',async()=>mockSource(async()=>{
 const ctx=context();ctx.env.RESPONSE_MASK='test-format-mask';
 const result=await(await worker.fetch(form(),ctx.env)).json();
 assert.equal(result.responseDecoded,true);assert.equal(result.published,true);
 assert.equal(ctx.data.get('football:catalog').channels[0].streamUrl,channel);
 assert.ok(!JSON.stringify(result).includes(ctx.env.RESPONSE_MASK));
},{encoded:true}));
