import test from 'node:test';
import assert from 'node:assert/strict';
import probe from '../../cloudflare-site/src/catalog-check.mjs';
const env={PROBE_KEY:'test-probe-00000000000000000000',RESPONSE_MASK:'test-format-mask'};
const id='4973946197000000001';
const req=(fields={})=>new Request('https://owned.invalid/api/internal/catalog-check',{method:'POST',body:new URLSearchParams({probe_key:env.PROBE_KEY,channel_id:id,...fields})});
const payload={data:[{name:'private-name',url:'https://stream.invalid/private-path?token=private-token',urlType:1,userAgent:'private-agent',headers:{Authorization:'private-credential',Referer:'https://private.invalid/'},drm:{type:'private-type',license:'private-license',requestProperties:{key:'private-key'}}}],servers:{private:'private-server'}};
async function run(body=JSON.stringify(payload),{fields={},status=200,encoded=false}={}){
 let calls=0,saves=0;globalThis.fetch=async(url,opt)=>{
  calls++;assert.equal(url,'https://def001.ycnapi.com/api/channel/'+id);assert.equal(opt.redirect,'manual');assert.equal(opt.headers.Authorization,undefined);
  if(encoded){const mask=new TextEncoder().encode(env.RESPONSE_MASK+'1793253408');body=btoa(Array.from(new TextEncoder().encode(body),(v,i)=>String.fromCharCode(v^mask[i%mask.length])).join(''));}
  return new Response(body,{status,headers:{t:'1793253408','Content-Type':'application/json'}});
 };
 try{return{result:await(await probe.fetch(req(fields),env,()=>{saves++;throw Error('no save');})).json(),calls,saves};}
 finally{globalThis.fetch=async()=>{throw Error('No real network');};}
}
test('one metadata request exposes shape without URLs, credentials or saving',async()=>{
 const {result,calls,saves}=await run();assert.equal(calls,1);assert.equal(saves,0);assert.equal(result.state,'PLAYBACK_METADATA');assert.equal(result.published,false);assert.equal(result.streamCount,1);
 assert.equal(result.streams[0].urlScheme,'https:');assert.equal(result.streams[0].headerCount,2);assert.equal(result.streams[0].drmPresent,true);
 assert.ok(!JSON.stringify(result).includes('private-'));assert.equal(result.channelId,id);
});
test('encoded response uses existing decoder and still redacts values',async()=>{
 const {result}=await run(undefined,{encoded:true});assert.equal(result.responseDecoded,true);assert.equal(result.state,'PLAYBACK_METADATA');assert.ok(!JSON.stringify(result).includes('private-'));
});
test('invalid ID, conflicting save or section and auth failure make no request',async()=>{
 let calls=0;globalThis.fetch=async()=>{calls++;throw Error('No network');};
 for(const [fields,status] of [[{channel_id:'https://other.invalid'},400],[{publish:'yes'},400],[{category_id:'4'},400],[{probe_key:'wrong'},401]])assert.equal((await probe.fetch(req(fields),env)).status,status);
 assert.equal(calls,0);
});
test('HTTP rejection and malformed JSON never expose raw payload',async()=>{
 const a=await run('private-error',{status:403});assert.equal(a.result.sourceHTTP,403);assert.equal(a.result.state,'HTTP_ERROR');
 const b=await run('{"private-unknown":true}');assert.equal(b.result.state,'INVALID');assert.ok(!JSON.stringify(b.result).includes('private-'));
});
test('empty and malformed record are distinguished',async()=>{
 assert.equal((await run('{"data":[]}')).result.state,'EMPTY');assert.equal((await run('{"data":[null]}')).result.state,'INVALID');
});
test('opaque URLs are described but never opened',async()=>{
 const {result,calls}=await run('{"data":[{"url":"opaque-private-payload"}]}');assert.equal(result.streams[0].urlScheme,'unknown');assert.equal(calls,1);
});
