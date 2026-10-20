import fs from 'node:fs';
import assert from 'node:assert/strict';
import {webcrypto} from 'node:crypto';
globalThis.crypto ??= webcrypto;
const source=fs.readFileSync(new URL('../cloudflare-site/src/worker.js',import.meta.url),'utf8')
  .replace('import { DurableObject } from "cloudflare:workers";','class DurableObject {}');
const {SignalingRoom}=await import('data:text/javascript;charset=utf-8,'+encodeURIComponent(source));
const state=new Map();
const storage={async get(k){return structuredClone(state.get(k))},async put(k,v){state.set(k,structuredClone(v))},async delete(k){return state.delete(k)},
  async list({prefix='',startAfter='',limit=1000}={}){return new Map([...state].filter(([k])=>k.startsWith(prefix)&&k>startAfter).sort(([a],[b])=>a.localeCompare(b)).slice(0,limit))}};
const env={SUPER_ADMIN_USERNAME:'owner',HUAWEI_PUSH_APP_ID:'123456789',HUAWEI_PUSH_APP_SECRET:'test-server-secret'};
const room=new SignalingRoom({storage,getWebSockets:()=>[],waitUntil:p=>p.catch(()=>{})},env);
for(const username of ['owner','alice','bob'])await storage.put('user:'+username,{username,displayName:username});
const owner=await room.createSession('owner'),alice=await room.createSession('alice'),bob=await room.createSession('bob');
async function api(path,body,method='POST'){
  const response=await room.api(new Request('https://test.example'+path,{method,headers:{'Content-Type':'application/json'},...(method==='GET'?{}:{body:JSON.stringify(body||{})})}));
  return {status:response.status,data:await response.json()};
}
assert.equal((await api('/api/legal-consent',{version:'2026-10-20',accepted:true})).status,401);
assert.equal((await api('/api/legal-consent',{token:alice,version:'old',accepted:true})).status,400);
assert.equal((await api('/api/legal-consent',{token:alice,version:'2026-10-20',accepted:true})).status,200);
assert.equal(state.get('legal-consent:alice').accepted,true);
await api('/api/legal-consent',{token:alice,version:'2026-10-20',accepted:false});
assert.equal(state.get('legal-consent:alice').accepted,false);
assert.equal(state.has('legal-consent:bob'),false);
console.log('PASS consent is authenticated, versioned, withdrawable and scoped to the account');
assert.equal((await api('/api/account-deletion-request',{token:alice,username:'bob',confirmation:'alice'})).status,403);
assert.equal((await api('/api/account-deletion-request',{token:alice,confirmation:'yes'})).status,400);
const requested=await api('/api/account-deletion-request',{token:alice,confirmation:'alice',reason:'حذف بياناتي'});
assert.equal(requested.status,200);assert.equal(requested.data.deleted,false);assert.ok(state.has('user:alice'));
assert.equal((await api('/api/account-deletion-request',{token:alice,confirmation:'alice'})).data.request.id,requested.data.request.id);
assert.equal((await api('/api/account-deletion-request?token='+bob,null,'GET')).data.request,null);
assert.equal((await api('/api/admin/deletion-requests?token='+alice,null,'GET')).status,403);
assert.equal((await api('/api/admin/deletion-requests?token='+owner,null,'GET')).data.requests.length,1);
await api('/api/account-deletion-request',{token:alice,action:'cancel'});
assert.equal((await api('/api/admin/deletion-requests?token='+owner,null,'GET')).data.requests.length,0);
assert.equal(state.get('deletion-request:alice').status,'cancelled');
assert.ok(await room.session(alice));
console.log('PASS deletion request requires exact confirmation, is idempotent and cancellable, and never deletes data');
await storage.put('post:one',{id:'one',author:'alice',text:'منشور',comments:[],likes:[]});
assert.equal((await api('/api/report',{token:bob,targetType:'post',targetId:'missing',reason:'محتوى مخالف'})).status,404);
assert.equal((await api('/api/report',{token:bob,targetType:'invalid',targetId:'one',reason:'محتوى مخالف'})).status,400);
assert.equal((await api('/api/report',{token:bob,targetId:'one',reason:''})).status,400);
assert.equal((await api('/api/report',{token:alice,targetId:'one',reason:'محتوى مخالف'})).status,400);
const report=await api('/api/report',{token:bob,targetId:'one',reason:'محتوى مخالف'});
assert.equal(report.status,200);assert.ok(report.data.id);
assert.equal((await api('/api/report',{token:bob,targetId:'one',reason:'بلاغ مكرر'})).data.alreadyReported,true);
assert.equal((await api('/api/admin/reports?token='+bob,null,'GET')).status,403);
assert.equal((await api('/api/admin/reports?token='+owner,null,'GET')).data.reports[0].targetUsername,'alice');
await api('/api/admin/action',{token:owner,action:'resolve_report',reportId:report.data.id});
assert.equal(state.get('reports')[0].resolved,true);
await storage.delete('report-rate:bob');
for(let i=0;i<6;i++){
  await storage.put('post:rate'+i,{id:'rate'+i,author:'alice'});
  assert.equal((await api('/api/report',{token:bob,targetId:'rate'+i,reason:'محتوى مخالف'})).status,i<5?200:429);
}
console.log('PASS reports validate content access, reject duplicates, rate-limit abuse and reach owner-only review');
const device='H'.repeat(140);
await api('/api/push/register',{token:bob,deviceToken:device,provider:'hms'});
assert.deepEqual(state.get('hmsTokens:bob'),[device]);
await api('/api/push/register',{token:alice,deviceToken:device,provider:'hms'});
assert.deepEqual(state.get('hmsTokens:bob'),[]);assert.equal(state.get('hmsOwner:'+device),'alice');
assert.equal((await api('/api/push/register',{token:alice,deviceToken:device,provider:'unknown'})).status,400);
const status=(await api('/api/push/status?token='+alice+'&provider=hms',null,'GET')).data;
assert.equal(status.provider,'hms');assert.equal(status.serverConfigured,true);assert.equal(status.registeredDevices,1);
assert.ok(!JSON.stringify(status).includes('test-server-secret'));
console.log('PASS HMS tokens use authenticated ownership, move between accounts and never expose secrets');
const originalFetch=globalThis.fetch,outbound=[];
globalThis.fetch=async(url,options)=>{
  outbound.push({url,options});
  return url.includes('/oauth2/')?new Response(JSON.stringify({access_token:'test-access',expires_in:3600})):
    new Response(JSON.stringify({code:'80000000'}));
};
try{
  const sent=await room.sendHmsToUser('alice',{type:'call-request',from:'bob',to:'alice',callId:'call-12345'},'45s');
  assert.equal(sent.accepted,1);assert.equal(sent.attempted,1);
  const packet=JSON.parse(outbound[1].options.body);
  assert.deepEqual(packet.message.token,[device]);assert.equal(packet.message.android.urgency,'HIGH');
  assert.equal(packet.message.android.ttl,'45s');assert.equal(JSON.parse(packet.message.data).callId,'call-12345');
  assert.equal(outbound[1].options.headers.Authorization,'Bearer test-access');
  assert.ok(!outbound[1].options.body.includes('test-server-secret'));
  await room.sendHmsToUser('alice',{type:'chat',from:'bob',to:'alice'},'60s');
  assert.equal(outbound.filter(r=>r.url.includes('/oauth2/')).length,1,'cache server OAuth token');
  globalThis.fetch=async()=>new Response(JSON.stringify({code:'80300007'}));
  assert.equal((await room.sendHmsToUser('alice',{type:'chat'},'60s')).accepted,0);
  assert.deepEqual(state.get('hmsTokens:alice'),[]);
}finally{globalThis.fetch=originalFetch;}
console.log('PASS actual HMS OAuth and push packets, token caching, expiry and invalid-token cleanup (mock network only)');
console.log('ALL APPGALLERY PRIVACY AND HMS TESTS PASSED');
