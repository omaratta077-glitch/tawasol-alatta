import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import probe from '../../cloudflare-site/src/catalog-check.mjs';
const env={PROBE_KEY:'test-probe-00000000000000000000',RESPONSE_MASK:'test-format-mask'};
const channel='4973946197000000001';
const request=(extra={})=>new Request('https://owned.example/api/internal/catalog-check',{method:'POST',body:new URLSearchParams({probe_key:env.PROBE_KEY,channel_id:channel,...extra})});
const fixtures=[{url:'https://cdn.example/live.m3u8',headers:{},drm:null},
  {url:'http://test.new-redirect.online/live/index.m3u8?t=synthetic-signature',headers:{'User-Agent':'Synthetic Player'},drm:null}];
async function resolve(rows=fixtures,{native=true,status=200,encoded=false}={}){
 let calls=0;globalThis.fetch=async(url,options)=>{
  calls++;assert.equal(url,'https://def001.ycnapi.com/api/channel/'+channel);
  assert.equal(new Headers(options.headers).has('authorization'),false);
  let body=JSON.stringify({data:rows});
  if(encoded){const mask=new TextEncoder().encode(env.RESPONSE_MASK+'1793253408');body=btoa(Array.from(new TextEncoder().encode(body),(v,i)=>String.fromCharCode(v^mask[i%mask.length])).join(''));}
  return new Response(body,{status,headers:{t:'1793253408'}});
 };
 try{const response=await probe.fetch(request({native_playback:'yes'}),env,()=>{throw Error('No saves');},native);
  return {response,data:await response.json(),calls};
 }finally{globalThis.fetch=async()=>{throw Error('No real network');};}
}
test('native owner path preserves both alternatives, exact URL and source User-Agent',async()=>{
 const {response,data,calls}=await resolve();assert.equal(response.status,200);assert.equal(calls,1);
 assert.equal(data.sources.length,2);assert.deepEqual(data.sources.map(x=>x.url),fixtures.map(x=>x.url));
 assert.equal(data.sources[1].headers['User-Agent'],'Synthetic Player');assert.equal(data.sources[1].mimeType,'application/x-mpegURL');
 assert.ok(!JSON.stringify(data).includes(env.PROBE_KEY));
});
test('encoded metadata uses existing response-time decoder',async()=>{
 const {data}=await resolve(undefined,{encoded:true});assert.equal(data.ok,true);assert.equal(data.sources.length,2);
});
test('public probe form cannot enable disclosure by forging a form field',async()=>{
 const {data}=await resolve(undefined,{native:false});assert.equal(data.state,'PLAYBACK_METADATA');
 assert.equal(data.sources,undefined);assert.ok(!JSON.stringify(data).includes('synthetic-signature'));
});
test('unsupported protected, transformed, unsafe or unknown-header sources are not silently altered',async()=>{
 const bad=[{url:'https://cdn.example/x',drm:{type:'widevine'}},{url:'https://cdn.example/x',urlType:99},
  {url:'https://cdn.example/x',headers:{Authorization:'private-credential'}},{url:'http://other.example/live.m3u8'},
  {url:'https://127.0.0.1/x'},{url:'https://user:pass@cdn.example/x'},
  {url:'https://cdn.example/x',headers:{'User-Agent':'x\r\nHost: bad'}},{url:'https://cdn.example/x',headers:{Host:'bad'}},
  {url:'opaque-value'},{url:'https://cdn.example:8443/x'},
  {url:'https://cdn.example/x',headers:{constructor:'not a supported header'}}];
 const {response,data}=await resolve(bad);assert.equal(response.status,422);assert.equal(data.code,'NO_SUPPORTED_NATIVE_SOURCE');
 assert.ok(!JSON.stringify(data).includes('private-credential'));
});
test('source refusal does not become a playable payload',async()=>{
 for (const status of [403,404,429,500]) {
  const {response,data}=await resolve(undefined,{status});assert.equal(response.status,502);assert.equal(data.sourceHTTP,status);assert.equal(data.sources,undefined);
  assert.equal(data.stage,'PLAYBACK_API_HTTP');assert.equal(data.code,'SOURCE_HTTP_ERROR');assert.match(data.error,new RegExp('HTTP '+status));
  assert.match(data.error,/PLAYBACK_API_HTTP/);assert.ok(!JSON.stringify(data).includes('synthetic-signature'));
 }
});
test('Cloudflare error code is identified without echoing the rejection body',async()=>{
 globalThis.fetch=async()=>new Response('error code: 1042 private-rejection-body',{status:404});
 try {const data=await(await probe.fetch(request(),env,null,true)).json();
  assert.equal(data.platformCode,'CLOUDFLARE_1042');assert.match(data.error,/HTTP 404/);assert.match(data.error,/CLOUDFLARE_1042/);
  assert.ok(!JSON.stringify(data).includes('private-rejection-body'));
 } finally {globalThis.fetch=async()=>{throw Error('No real network');};}
});
test('native connection error stays distinct from an HTTP rejection',async()=>{
 globalThis.fetch=async()=>{throw Error('https://private.example/?secret=private-value');};
 try {const data=await(await probe.fetch(request(),env,null,true)).json();assert.equal(data.sourceHTTP,null);
  assert.equal(data.code,'SOURCE_NETWORK_ERROR');assert.equal(data.stage,'PLAYBACK_API_FETCH');assert.ok(!JSON.stringify(data).includes('private-value'));
 } finally {globalThis.fetch=async()=>{throw Error('No real network');};}
});
test('native path caps alternatives and keeps upstream order',async()=>{
 const {data}=await resolve(Array.from({length:15},(_,i)=>({url:`https://cdn.example/${i}.m3u8`})));
 assert.equal(data.sources.length,10);assert.equal(data.skipped,5);assert.deepEqual(data.sources.map(x=>x.index),[0,1,2,3,4,5,6,7,8,9]);
});
async function rotatedPlayback({freshId='4973946197000000002',initialStatus=404,retryStatus=200,duplicate=false,platform=false,
 storedCategory='beIN SPORTS (1080P)',storedCategoryId=null}={}) {
 const urls=[];const category='652497361000000001';
 globalThis.fetch=async(url,options)=>{
  urls.push(url);assert.equal(options.redirect,'manual');assert.ok(options.signal);
  assert.equal(new Headers(options.headers).has('authorization'),false);
  if(url.endsWith('/channel/'+channel))return new Response(platform?'error code: 1042':'missing',{status:initialStatus});
  if(url.endsWith('/categories'))return Response.json({data:[{id:category,name:'beIN SPORTS (1080P)'}]});
  if(url.endsWith('/categories/'+category+'/channels'))return Response.json({data:[
   {id:freshId,name:'beIN SPORTS 1'},...(duplicate?[{id:'4973946197000000003',name:'bein sports1'}]:[])]});
  assert.ok(url.endsWith('/channel/'+freshId));return Response.json({data:fixtures},{status:retryStatus});
 };
 try {const response=await probe.fetch(request(),env,null,true,{name:'beIN SPORTS 1',category:storedCategory,categoryId:storedCategoryId});
  return {data:await response.json(),status:response.status,urls};
 }finally{globalThis.fetch=async()=>{throw Error('No real network');};}
}
test('404 resolves current section and channel by exact labels then retries the new ID once',async()=>{
 const {data,status,urls}=await rotatedPlayback();assert.equal(status,200);assert.equal(data.ok,true);assert.equal(data.idRefreshed,true);
 assert.equal(data.channelId,'4973946197000000002');assert.equal(data.sources.length,2);assert.equal(urls.length,4);
 assert.equal(urls.filter(x=>x.endsWith('/channel/'+channel)).length,1);
});
test('a current unchanged ID is not retried and is reported distinctly',async()=>{
 const {data,urls}=await rotatedPlayback({freshId:channel});assert.equal(data.code,'FRESH_ID_UNCHANGED');assert.equal(urls.length,3);assert.equal(data.sourceHTTP,404);
});
test('another 404 after ID refresh stops without a loop',async()=>{
 const {data,urls}=await rotatedPlayback({retryStatus:404});assert.equal(urls.length,4);assert.equal(data.sourceHTTP,404);
 assert.equal(data.idRefreshed,true);assert.match(data.error,/ID_REFRESHED/);assert.equal(data.sources,undefined);
});
test('ambiguous names do not choose an arbitrary source ID',async()=>{
 const {data,urls}=await rotatedPlayback({duplicate:true});assert.equal(data.code,'CHANNEL_AMBIGUOUS');assert.equal(urls.length,3);assert.equal(data.sources,undefined);
});
test('category matching ignores display brackets/bidi marks while retaining quality',async()=>{
 const good=await rotatedPlayback({storedCategory:'\u200fbeIN SPORTS ) (1080P)\u200e'});assert.equal(good.data.ok,true);assert.equal(good.urls.length,4);
 const different=await rotatedPlayback({storedCategory:'beIN SPORTS (720P)'});assert.equal(different.data.code,'CATEGORY_NOT_FOUND');assert.equal(different.urls.length,2);
});
test('source category ID takes priority over a local display label only when present in the fresh list',async()=>{
 assert.equal((await rotatedPlayback({storedCategory:'local display label',storedCategoryId:'652497361000000001'})).data.ok,true);
 assert.equal((await rotatedPlayback({storedCategory:'local display label',storedCategoryId:'652497361000000099'})).data.code,'CATEGORY_NOT_FOUND');
});
test('403 and Cloudflare platform rejection do not trigger an ID recovery',async()=>{
 assert.equal((await rotatedPlayback({initialStatus:403})).urls.length,1);
 const cf=await rotatedPlayback({platform:true});assert.equal(cf.urls.length,1);assert.equal(cf.data.platformCode,'CLOUDFLARE_1042');
});
test('owner endpoint uses synchronized section identity and ignores it after a manual source override',async()=>{
 const source=(await readFile(new URL('../../cloudflare-site/src/worker.js',import.meta.url),'utf8')).replace('import { DurableObject } from "cloudflare:workers";','class DurableObject {}');
 const {SignalingRoom}=await import('data:text/javascript,'+encodeURIComponent(source));
 const category='652497361000000001',fresh='4973946197000000002';
 for(const bound of [true,false]) {
  const channelRecord={id:'stable',name:'beIN SPORTS 1',category:'local display',streamUrl:channel,format:'ytv',enabled:true};
  const sync={categoryId:category,categoryName:'original source label',bindings:{stable:bound?channel:'999'}};
  const map=new Map([['football:catalog',{channels:[channelRecord]}],['football:source-sync',sync]]);
  const storage={async get(k){return structuredClone(map.get(k));},async put(k,v){map.set(k,structuredClone(v));}};
  const room=new SignalingRoom({storage,getWebSockets:()=>[]},env);room.requireAdmin=async()=>({});
  let calls=0;globalThis.fetch=async url=>{calls++;
   if(url.endsWith('/channel/'+channel))return new Response('missing',{status:404});
   if(url.endsWith('/categories'))return Response.json({data:[{id:category,name:'renamed source label'}]});
   if(url.endsWith('/categories/'+category+'/channels'))return Response.json({data:[{id:fresh,name:'beIN SPORTS 1'}]});
   assert.ok(url.endsWith('/channel/'+fresh));return Response.json({data:fixtures});
  };
  try {
   const response=await room.footballPlaybackTest(new Request('https://owned.example/api/admin/football/playback-test',{method:'POST'}),
    {token:'owner',channelId:'stable',categoryId:category,category:'renamed source label'});
   const result=await response.json();assert.equal(response.status,bound?200:502);assert.equal(calls,bound?4:2);
   if(bound)assert.equal(result.channelId,fresh);else assert.equal(result.code,'CATEGORY_NOT_FOUND');
   assert.deepEqual(map.get('football:source-sync'),sync);assert.deepEqual(map.get('football:catalog').channels,[channelRecord]);
  } finally {globalThis.fetch=async()=>{throw Error('No real network');};}
 }
});
test('app endpoint authenticates first, resolves current stable catalog ID and never persists media data',async()=>{
 const source=(await readFile(new URL('../../cloudflare-site/src/worker.js',import.meta.url),'utf8')).replace('import { DurableObject } from "cloudflare:workers";','class DurableObject {}');
 const {SignalingRoom}=await import('data:text/javascript,'+encodeURIComponent(source));
 const map=new Map([['football:catalog',{channels:[{id:'stable-channel',format:'ytv',enabled:true,streamUrl:channel}]}]]);
 const storage={async get(k){return structuredClone(map.get(k));},async put(k,v){map.set(k,structuredClone(v));}};
 const room=new SignalingRoom({storage,getWebSockets:()=>[]},env);
 room.requireAdmin=async token=>token==='owner'?{}:{error:Response.json({ok:false},{status:403})};
 const req=new Request('https://owned.example/api/admin/football/playback-test',{method:'POST'});
 let calls=0;globalThis.fetch=async url=>{calls++;assert.equal(url,'https://def001.ycnapi.com/api/channel/'+channel);return Response.json({data:fixtures});};
 try {
  assert.equal((await room.footballPlaybackTest(req,{token:'outsider',channelId:'stable-channel'})).status,403);
  assert.equal((await room.footballPlaybackTest(req,{token:'owner',channelId:'made-up-source-id'})).status,404);assert.equal(calls,0);
  const result=await room.footballPlaybackTest(req,{token:'owner',channelId:'stable-channel'});assert.equal(result.status,200);
  assert.equal((await result.json()).sources.length,2);assert.match(result.headers.get('cache-control'),/private, no-store/);
  assert.equal((await room.footballPlaybackTest(req,{token:'owner',channelId:'stable-channel'})).status,429);
  assert.equal(calls,1);assert.equal(map.size,2);assert.deepEqual(map.get('football:catalog').channels[0].streamUrl,channel);
 } finally {globalThis.fetch=async()=>{throw Error('No real network');};}
});

test('viewer playback requires a valid session, isolates throttling and keeps admin authorization',async()=>{
 const source=(await readFile(new URL('../../cloudflare-site/src/worker.js',import.meta.url),'utf8')).replace('import { DurableObject } from "cloudflare:workers";','class DurableObject {}');
 const {SignalingRoom}=await import('data:text/javascript,'+encodeURIComponent(source));
 const map=new Map([['football:catalog',{channels:[{id:'stable',format:'ytv',enabled:true,streamUrl:channel}]}]]);
 const room=new SignalingRoom({storage:{async get(k){return structuredClone(map.get(k));},async put(k,v){map.set(k,structuredClone(v));}},getWebSockets:()=>[]},env);
 room.session=async token=>['alice','bob'].includes(token)?{username:token}:null;
 room.requireAdmin=async()=>({error:Response.json({ok:false},{status:403})});
 const req=new Request('https://owned.example/api/football/playback',{method:'POST'});
 let calls=0;globalThis.fetch=async()=>{calls++;return Response.json({data:fixtures});};
 try{
  assert.equal((await room.footballPlaybackTest(req,{token:'expired',channelId:'stable'},true)).status,401);
  assert.equal((await room.footballPlaybackTest(req,{token:'alice',channelId:'unknown'},true)).status,404);
  assert.equal(calls,0);
  assert.equal((await room.footballPlaybackTest(req,{token:'alice',channelId:'stable'},true)).status,200);
  assert.equal((await room.footballPlaybackTest(req,{token:'alice',channelId:'stable'},true)).status,429);
  assert.equal((await room.footballPlaybackTest(req,{token:'bob',channelId:'stable'},true)).status,200);
  assert.equal((await room.footballPlaybackTest(req,{token:'bob',channelId:'stable'})).status,403);
  assert.equal(calls,2);
 }finally{globalThis.fetch=async()=>{throw Error('No real network');};}
});
test('observed HTTP source families pass while unrelated HTTP hosts remain rejected',async()=>{
 for(const host of ['h50.cdnravon.buzz','h32.streamalvo.buzz','h46.vidorise.buzz']){
  assert.equal((await resolve([{url:'http://'+host+'/live/index.m3u8'}])).data.sources.length,1);
 }
 assert.equal((await resolve([{url:'http://unrelated.buzz/live/index.m3u8'}])).response.status,422);
});
