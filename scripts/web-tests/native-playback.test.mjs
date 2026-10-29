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
