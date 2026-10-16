import fs from 'node:fs';
import assert from 'node:assert/strict';
import {webcrypto} from 'node:crypto';
if(!globalThis.crypto)globalThis.crypto=webcrypto;
const code=fs.readFileSync(new URL('../cloudflare-site/src/worker.js',import.meta.url),'utf8')
  .replace('import { DurableObject } from "cloudflare:workers";', 'class DurableObject {}');
const {SignalingRoom}=await import('data:text/javascript;charset=utf-8,'+encodeURIComponent(code));
const map=new Map();
const storage={async get(k){return map.get(k)}, async put(k,v){map.set(k,v)}, async delete(k){map.delete(k)}};
const room=new SignalingRoom({storage},{});
const api=async (method,path,body)=>{
 const response=await room.api(new Request('https://test.example'+path,{method,headers:{'Content-Type':'application/json'},...(body?{body:JSON.stringify(body)}:{})}));
 return {status:response.status,body:await response.json()};
};
await storage.put('user:alice',{username:'alice',displayName:'Alice'});
await storage.put('user:bob',{username:'bob',displayName:'Bob'});
await storage.put('friends:alice',['bob']);
await storage.put('friends:bob',['alice']);
const alice=await room.createSession('alice');
const bob=await room.createSession('bob');
const callId='laptop-to-phone-a';
const call={type:'call-request',from:'forged',to:'bob',callId,callType:'video',signalId:'signal-a',createdAt:Date.now()};
const post=await api('POST','/api/message-send',{token:alice,to:'bob',text:'',mediaType:'call-signal',media:JSON.stringify(call)});
assert.equal(post.status,200);
const phone=await api('GET','/api/call-signals?token='+bob);
assert.equal(phone.status,200);
assert.equal(phone.body.signals.length,1);
assert.equal(phone.body.signals[0].from,'alice','the backend must authenticate the true caller');
assert.equal(phone.body.signals[0].callId,callId);
console.log('PASS desktop stored invitation reaches phone fallback queue');
const rePoll=await api('GET','/api/call-signals?token='+bob);
assert.equal(rePoll.body.signals.length,0);
console.log('PASS invitation delivered once');
const forged=await api('GET','/api/call-signals?token='+alice);
assert.equal(forged.body.signals.length,0);
console.log('PASS call invitation not readable by third-party / caller');
const cancel={type:'hangup',callId,callType:'video',signalId:'signal-b',createdAt:Date.now()};
await api('POST','/api/message-send',{token:alice,to:'bob',mediaType:'call-signal',media:JSON.stringify(cancel)});
const cancelPoll=await api('GET','/api/call-signals?token='+bob);
assert.deepEqual(cancelPoll.body.signals.map(x=>x.type),['hangup']);
console.log('PASS caller cancellation reaches phone fallback');
const socket={deserializeAttachment:()=>({user:'alice',token:alice}),send:()=>{}};
await room.webSocketMessage(socket,JSON.stringify({
  type:'call-request',to:'bob',callId:'socket-first',callType:'audio',
  signalId:'one-event',createdAt:Date.now()
}));
// A redundant HTTP copy from the same sender must NOT duplicate the queued invite.
await api('POST','/api/message-send',{token:alice,to:'bob',mediaType:'call-signal',
 media:JSON.stringify({type:'call-request',callId:'socket-first',callType:'audio',signalId:'one-event',createdAt:Date.now()})});
const dedup=await api('GET','/api/call-signals?token='+bob);
assert.equal(dedup.body.signals.length,1);
assert.equal(dedup.body.signals[0].callId,'socket-first');
console.log('PASS WebSocket-first incoming call queues once even with matching HTTP');
await storage.put('lastSeen:bob',Date.now()-62000);
const publicProfile=await api('GET','/api/profile-public?token='+alice+'&username=bob');
assert.ok(publicProfile.body.user.lastSeen>0);
assert.equal(publicProfile.body.user.isOnline,false);
console.log('PASS last-active timestamp included in profile');
console.log('ALL MOBILE CALL FALLBACK SMOKE TESTS PASSED');
