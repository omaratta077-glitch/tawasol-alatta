import fs from 'node:fs';
import assert from 'node:assert/strict';
import {webcrypto} from 'node:crypto';
if(!globalThis.crypto)globalThis.crypto=webcrypto;
const source=fs.readFileSync(new URL('../cloudflare-site/src/worker.js',import.meta.url),'utf8')
 .replace('import { DurableObject } from "cloudflare:workers";', 'class DurableObject {}');
const {SignalingRoom}=await import('data:text/javascript;charset=utf-8,'+encodeURIComponent(source));
const state=new Map(), queued=[];
const storage={async get(k){return state.get(k)},async put(k,v){state.set(k,v)},async delete(k){state.delete(k)}};
const room=new SignalingRoom({storage,waitUntil(task){queued.push(task)}},
 {FIREBASE_SERVICE_ACCOUNT_JSON:'{"project_id":"push-test"}'});
for(const name of ['alice','bob','carol'])await storage.put('user:'+name,{username:name});
await storage.put('friends:alice',['bob']);
await storage.put('friends:bob',['alice']);
const [alice,bob,carol]=await Promise.all(['alice','bob','carol'].map(u=>room.createSession(u)));
async function request(path, body={}) {
 const res=await room.api(new Request('https://test.example'+path,{method:'POST',
   headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}));
 return {status:res.status,json:await res.json()};
}
const sent=[];
room.deliverUserPush=async(to,ev)=>{sent.push({to,...ev})};
async function drain(){while(queued.length)await Promise.all(queued.splice(0))}
const device='T'.repeat(140);
assert.equal((await request('/api/push/register',{token:bob,deviceToken:device})).status,200);
assert.equal(state.get('fcmOwner:'+device),'bob');
const denied=await request('/api/message-send',{token:carol,to:'bob',text:'blocked'});
assert.equal(denied.status,403);await drain();assert.equal(sent.length,0);
const message=await request('/api/message-send',{token:alice,to:'bob',text:'PRIVATE MESSAGE BODY'});
assert.equal(message.status,200);await drain();
assert.equal(sent.length,1);assert.equal(sent[0].to,'bob');
assert.equal(sent[0].type,'chat');assert.equal(sent[0].from,'alice');
assert.equal(sent[0].eventId,message.json.message.id);
assert.ok(!JSON.stringify(sent[0]).includes('PRIVATE MESSAGE BODY'));
const signal=await request('/api/message-send',{token:alice,to:'bob',text:'',mediaType:'call-signal',
 media:JSON.stringify({type:'call-request',callId:'a'.repeat(24),callType:'audio'})});
assert.equal(signal.status,200);await drain();
assert.equal(sent.length,1,'call signaling must not create social message notifications');
console.log('PASS authenticated text message push, private body excluded; no duplicate for call signal');
const post=await request('/api/posts',{token:alice,text:'hello'});
assert.equal(post.status,200);const postId=post.json.post.id;
assert.equal((await request('/api/post-like',{token:bob,postId})).status,200);
await drain();assert.equal(sent.at(-1).type,'like');assert.equal(sent.at(-1).to,'alice');
assert.equal((await request('/api/post-comment',{token:bob,postId,text:'hello'})).status,200);
await drain();assert.equal(sent.at(-1).type,'comment');assert.equal(sent.at(-1).to,'alice');
assert.equal((await request('/api/post-react',{token:bob,postId,reaction:'love'})).status,200);
await drain();assert.equal(sent.at(-1).type,'reaction');
console.log('PASS likes, comments and reactions schedule push to post owner');
assert.equal((await request('/api/friend-request',{token:carol,username:'alice'})).status,200);
await drain();assert.equal(sent.at(-1).type,'friend-request');assert.equal(sent.at(-1).to,'alice');
assert.equal((await request('/api/friend-action',{token:alice,username:'carol',action:'accept'})).status,200);
await drain();assert.equal(sent.at(-1).type,'friend-accepted');assert.equal(sent.at(-1).to,'carol');
console.log('PASS friend requests and accepted notifications reach correct account');
// يؤدي تسجيل الدخول بحساب آخر على الجهاز نفسه إلى نقل الملكية؛ امنع تسرب البيانات.
assert.equal((await request('/api/push/register',{token:alice,deviceToken:device})).status,200);
assert.deepEqual(state.get('fcmTokens:bob'),[]);
assert.deepEqual(state.get('fcmTokens:alice'),[device]);
assert.equal((await request('/api/push/unregister',{token:alice,deviceToken:device})).status,200);
assert.deepEqual(state.get('fcmTokens:alice'),[]);
console.log('PASS device token cannot remain linked to the previous account');
// تحقّق من حزمة FCM v1 الفعلية التي تحتوي بيانات فقط (بلا رسائل أو رمز تفويض).
await storage.put('fcmTokens:bob',[device]);
room.fcmAccessToken=async()=> 'test-auth';
const originalFetch=globalThis.fetch, outbound=[];
globalThis.fetch=async(url,opts)=>{
 outbound.push({url,payload:JSON.parse(opts.body)});
 return new Response('{}',{status:200});
};
try {
 await room.sendFcmToUser('bob',{type:'chat',from:'alice',to:'bob',eventId:'a'.repeat(36)},'86400s');
} finally {globalThis.fetch=originalFetch}
assert.equal(outbound.length,1);
assert.equal(outbound[0].payload.message.android.priority,'HIGH');
assert.equal(outbound[0].payload.message.android.ttl,'86400s');
assert.equal(outbound[0].payload.message.token,device);
assert.equal(outbound[0].payload.message.data.type,'chat');
assert.ok(!JSON.stringify(outbound).includes('PRIVATE MESSAGE BODY'));
console.log('PASS FCM v1 data-only high-priority packet, no private message body');
console.log('ALL SOCIAL PUSH TESTS PASSED');
