import fs from 'node:fs';
import assert from 'node:assert/strict';
import {webcrypto} from 'node:crypto';
if(!globalThis.crypto) globalThis.crypto=webcrypto;
const code=fs.readFileSync(new URL('../cloudflare-site/src/worker.js',import.meta.url),'utf8')
  .replace('import { DurableObject } from "cloudflare:workers";', 'class DurableObject {}');
const {SignalingRoom}=await import('data:text/javascript;charset=utf-8,'+encodeURIComponent(code));
const memory=new Map();
const ctx={storage:{async get(k){return memory.get(k)},async put(k,v){memory.set(k,v)}},waitUntil(){}};
const room=new SignalingRoom(ctx,{
  FIREBASE_SERVICE_ACCOUNT_JSON:JSON.stringify({client_email:'test@example.test',private_key:'test',project_id:'project-test'}),
  FIREBASE_PROJECT_ID:'project-test'
});
room.fcmAccessToken=async()=> 'test-token';
const device='A'.repeat(140);
await ctx.storage.put('fcmTokens:bob',[device]);
const captured=[];
const fetchBefore=globalThis.fetch;
globalThis.fetch=async(url,options)=>{
  const json=JSON.parse(options.body);
  captured.push(json.message);
  return new Response(JSON.stringify({name:'projects/project-test/messages/mock'}),{status:200,headers:{'Content-Type':'application/json'}});
};
try{
  await room.deliverCallPush('bob',{type:'call-request',from:'alice',callId:'call-abc123',callType:'video',createdAt:Date.now()});
  await room.deliverUserPush('bob',{type:'chat',from:'alice',eventId:'event-chat-abc123',createdAt:Date.now()});
  const testing=await room.sendFcmToUser('bob',{type:'push-test',to:'bob',from:'',eventId:'event-test-abc123',createdAt:String(Date.now())},'60s');
  assert.equal(testing.accepted,1);
  assert.equal(captured.length,3);
  for(const item of captured){
    assert.equal(item.token,device);
    assert.equal(Object.hasOwn(item.data,'from'),false,'reserved FCM data.from was sent');
    assert.equal(item.android.priority,'HIGH');
    assert.ok(item.android.ttl.endsWith('s'));
  }
  assert.equal(captured[0].data.sender,'alice');
  assert.equal(captured[0].data.callType,'video');
  assert.equal(captured[1].data.sender,'alice');
  assert.equal(captured[2].data.sender,'');
  console.log('PASS: call, chat and test FCM payloads contain no reserved from key');
}finally{globalThis.fetch=fetchBefore;}
