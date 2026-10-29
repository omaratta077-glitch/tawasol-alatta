import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import probe from '../../cloudflare-site/src/catalog-check.mjs';
const env={PROBE_KEY:'test-probe-00000000000000000000'},channel='4973946197000000001';
const form=(fields={})=>new Request('https://owned.invalid/api/internal/catalog-check',{method:'POST',body:new URLSearchParams({probe_key:env.PROBE_KEY,channel_id:channel,playback_video:'yes',...fields})});
async function run(rows,{fields={},status=200}={}){
 let calls=0,saves=0;globalThis.fetch=async(url)=>{calls++;assert.equal(url,'https://def001.ycnapi.com/api/channel/'+channel);return Response.json({data:rows},{status,headers:{t:'1793260792'}});};
 try{const response=await probe.fetch(form(fields),env,()=>{saves++;throw Error('no save');});return {response,text:await response.text(),calls,saves};}
 finally{globalThis.fetch=async()=>{throw Error('No network');};}
}
test('explicit play returns private nonce-protected page, without server media requests or saving',async()=>{
 const {response,text,calls,saves}=await run([{url:'https://media.example.org/live.m3u8?token=private-token',headers:{},drm:null}]);
 assert.equal(calls,1);assert.equal(saves,0);assert.match(response.headers.get('Content-Type'),/text\/html/);assert.equal(response.headers.get('Cache-Control'),'no-store');
 assert.equal(response.headers.get('Referrer-Policy'),'no-referrer');assert.match(response.headers.get('Content-Security-Policy'),/script-src 'nonce-/);
 assert.match(text,/<video[^>]*preload="none"/);assert.ok(!text.includes('src="https://media'));assert.ok(!text.includes(env.PROBE_KEY));
});
test('skips HTTP, headers, userAgent and DRM rather than dropping required configuration',async()=>{
 for(const row of [{url:'http://media.example.org/a'},{url:'https://media.example.org/a',headers:{Authorization:'private'}},{url:'https://media.example.org/a',userAgent:'private'},{url:'https://media.example.org/a',drm:{license:'private'}}]){
  const {text}=await run([row]);const result=JSON.parse(text);assert.equal(result.state,'NO_DIRECT_HTTPS');assert.ok(!text.includes('private'));assert.ok(!text.includes('media.example.org'));
 }
});
test('local addresses, credentials and nonstandard ports are not selected',async()=>{
 for(const url of ['https://127.0.0.1/live','https://host.local/live','https://[::1]/live','https://a:b@media.example.org/live','https://media.example.org:444/live'])
 assert.equal(JSON.parse((await run([{url}])).text).state,'NO_DIRECT_HTTPS');
});
test('auth, missing channel and catalog publication conflicts fail before fetch',async()=>{
 let calls=0;globalThis.fetch=async()=>{calls++;throw Error('no network');};
 for(const fields of [{probe_key:'wrong'},{channel_id:''},{category_id:'4'},{publish:'yes'}])assert.ok((await probe.fetch(form(fields),env)).status>=400);
 assert.equal(calls,0);
});
test('failed source still returns diagnostic JSON rather than a player',async()=>{
 const {response,text}=await run([],{status:404});assert.match(response.headers.get('Content-Type'),/application\/json/);assert.equal(JSON.parse(text).sourceHTTP,404);
});
test('client waits for click, captures media error and never copies the source URL',async()=>{
 const {text}=await run([{url:'https://media.example.org/live.m3u8?token=private-token'}]);
 const code=text.match(/<script nonce="[a-f0-9]+">([\s\S]*?)<\/script>/)[1];
 const listeners={},els={play:{},stop:{},status:{textContent:''},report:{value:'',focus(){},select(){}},copy:{}};let loads=0,copied='';
 const video={readyState:0,networkState:0,currentTime:0,videoWidth:0,videoHeight:0,error:null,canPlayType(){return '';},addEventListener(k,fn){listeners[k]=fn;},load(){loads++;},async play(){throw Object.assign(Error('private-token'),{name:'NotSupportedError'});},pause(){},removeAttribute(){}};els.video=video;
 const context={document:{getElementById:id=>els[id]},navigator:{clipboard:{async writeText(v){copied=v;}}},window:{addEventListener(){}},setTimeout(){return 1;},clearTimeout(){},Date,JSON,Number};
 vm.runInNewContext(code,context);assert.equal(loads,0);assert.ok(!els.report.value.includes('private-token'));
 await els.play.onclick();assert.equal(loads,1);video.error={code:4,message:'private-token'};listeners.error();await els.copy.onclick();
 const report=JSON.parse(copied);assert.equal(report.mediaErrorCode,4);assert.equal(report.playRequestError,'NotSupportedError');assert.equal(report.hlsCapability,'');
 assert.ok(!copied.includes('private-token'));assert.ok(!copied.includes('media.example.org'));assert.ok(!copied.includes(env.PROBE_KEY));
});
test('inline settings cannot break out of the script element',async()=>{
 const {text}=await run([{url:'https://media.example.org/live?token=</script><script>alert(1)</script>'}]);
 assert.equal((text.match(/<script /g)||[]).length,1);assert.equal((text.match(/<\/script>/g)||[]).length,1);
});
