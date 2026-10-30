import fs from 'node:fs';
import assert from 'node:assert/strict';
import { webcrypto } from 'node:crypto';
if(!globalThis.crypto) globalThis.crypto = webcrypto;
const code = fs.readFileSync(new URL('../cloudflare-site/src/worker.js', import.meta.url), 'utf8')
  .replace('import { DurableObject } from "cloudflare:workers";', 'class DurableObject {}');
const {SignalingRoom}=await import('data:text/javascript;charset=utf-8,'+encodeURIComponent(code));
const map=new Map(), tasks=[];
const storage={async get(k){return map.get(k)},async put(k,v){map.set(k,v)},async delete(k){map.delete(k)}};
const room=new SignalingRoom({storage,waitUntil(task){tasks.push(task)}}, {});
for(const user of ['alice','bob','mallory']) await storage.put('user:'+user,{username:user});
await storage.put('friends:alice',['bob']);
await storage.put('friends:bob',['alice']);
const alice=await room.createSession('alice'), bob=await room.createSession('bob');
async function api(path,method='POST',body={}){
 const res=await room.api(new Request('https://example.test'+path,{method,headers:{'Content-Type':'application/json'},...(method==='POST'?{body:JSON.stringify(body)}:{})}));
 return {status:res.status, value:await res.json()};
}
async function messages(session,peer){return (await api('/api/conversation?token='+session+'&with='+peer,'GET')).value.messages.filter(m=>m.mediaType==='call-event')}
const id='call-audio-unique-0001';
let x=await api('/api/call-signal','POST',{token:alice,to:'bob',signal:{type:'call-request',callType:'audio',callId:id}});
assert.equal(x.status,200);
assert.equal((await messages(alice,'bob')).length,1);
assert.equal((await messages(bob,'alice'))[0].callStatus,'ringing');
assert.equal((await messages(bob,'alice'))[0].callType,'audio');
await api('/api/message-send','POST',{token:alice,to:'bob',mediaType:'call-signal',media:JSON.stringify({type:'call-request',callType:'audio',callId:id})});
assert.equal((await messages(bob,'alice')).length,1,'duplicate HTTP/WS invitations must share one call history entry');
await api('/api/call-signal','POST',{token:bob,to:'alice',signal:{type:'call-reject',callId:id,reason:'rejected'}});
assert.equal((await messages(alice,'bob'))[0].callStatus,'rejected');
console.log('PASS one rejected-call record in both conversations, duplicate transport deduped');
const id2='call-video-unique-0002';
await api('/api/message-send','POST',{token:alice,to:'bob',mediaType:'call-signal',media:JSON.stringify({type:'call-request',callType:'video',callId:id2})});
await api('/api/message-send','POST',{token:bob,to:'alice',mediaType:'call-signal',media:JSON.stringify({type:'call-accept',callType:'video',callId:id2})});
assert.equal((await messages(bob,'alice')).find(x=>x.callId===id2).callStatus,'answered');
await api('/api/call-signal','POST',{token:alice,to:'bob',signal:{type:'hangup',callId:id2}});
assert.equal((await messages(bob,'alice')).find(x=>x.callId===id2).callStatus,'completed');
console.log('PASS accepted then ended video call updates one record');
const id3='call-unanswered-unique-0003';
await api('/api/call-signal','POST',{token:alice,to:'bob',signal:{type:'call-request',callType:'audio',callId:id3}});
const key='messages:alice:bob', stored=map.get(key);
stored.find(x=>x.callId===id3).ts=Date.now()-57000;
await storage.put(key,stored);
assert.equal((await messages(bob,'alice')).find(x=>x.callId===id3).callStatus,'missed');
console.log('PASS timed out unanswered call becomes missed in conversation');
assert.equal((await api('/api/push/status?token=bad','GET')).status,401);
const status=await api('/api/push/status?token='+bob,'GET');
assert.equal(status.status,200);assert.equal(status.value.registeredDevices,0);
assert.equal(status.value.serverConfigured,false);
assert.equal((await api('/api/push/test','POST',{token:bob})).status,409);
console.log('PASS authenticated push status and no false-positive delivery test when not configured');
// حاكِ رد FCM المقبول أو الفاشل دون إرسال طلب شبكة.
room.env.FIREBASE_SERVICE_ACCOUNT_JSON=JSON.stringify({project_id:'demo',client_email:'svc@demo',private_key:'test-not-real'});
room.fcmAccessToken=async()=> 'fake-token';
const device='F'.repeat(150);
assert.equal((await api('/api/push/register','POST',{token:bob,deviceToken:device})).status,200);
const originalFetch=globalThis.fetch;
try {
 globalThis.fetch=async()=>new Response(JSON.stringify({name:'projects/demo/messages/ok'}),{status:200});
 const passed=await api('/api/push/test','POST',{token:bob});
 assert.equal(passed.status,200);assert.equal(passed.value.acceptedByFirebase,1);
 assert.equal((await api('/api/push/test','POST',{token:bob})).status,429);
 // حساب آخر، والخدمة نفسها مع محاكاة فشل تفويض FCM.
 const failedDevice='G'.repeat(150);
 assert.equal((await api('/api/push/register','POST',{token:alice,deviceToken:failedDevice})).status,200);
 globalThis.fetch=async()=>new Response(JSON.stringify({error:{status:'PERMISSION_DENIED'}}),{status:403});
 const failed=await api('/api/push/test','POST',{token:alice});
 assert.equal(failed.status,502);assert.equal(failed.value.diagnostic,'FCM_HTTP_403');
} finally {globalThis.fetch=originalFetch;}
console.log('PASS FCM self-test distinguishes accepted push from HTTP 403 and rate limits repeats');

console.log('ALL CALL HISTORY/DIAGNOSTIC TESTS PASSED');
