import assert from 'node:assert/strict';
import fs from 'node:fs';
import {webcrypto} from 'node:crypto';
globalThis.crypto??=webcrypto;
const source=fs.readFileSync(new URL('../cloudflare-site/src/worker.js',import.meta.url),'utf8').replace('import { DurableObject } from "cloudflare:workers";','class DurableObject {}');
const {SignalingRoom}=await import('data:text/javascript;charset=utf-8,'+encodeURIComponent(source));
const data=new Map(),tasks=[],packets=[],pages=[],alarms=[];
const storage={async get(k){return structuredClone(data.get(k))},async put(k,v){data.set(k,structuredClone(v))},async delete(k){data.delete(k)},async setAlarm(t){alarms.push(t)},async list({prefix='',startAfter='',limit=1000}={}){const result=new Map([...data].filter(([k])=>k.startsWith(prefix)&&k>startAfter).sort(([a],[b])=>a<b?-1:1).slice(0,limit).map(([k,v])=>[k,structuredClone(v)]));if(prefix==='user:')pages.push(result.size);return result;}};
const ctx={storage,getWebSockets:()=>[],waitUntil(p){tasks.push(p)}};
const env={FIREBASE_SERVICE_ACCOUNT_JSON:'configured'};
function restart(){const r=new SignalingRoom(ctx,env);r.sendDevicePush=async(to,payload)=>{packets.push({to,...payload});return{accepted:1}};return r;}
let room=restart();const createdAt=Date.now()-1000;
for(const username of ['alice','bob','private','blocked','disabled',...Array.from({length:60},(_,i)=>'reader'+String(i).padStart(2,'0'))])data.set('user:'+username,{username,fullName:'Name '+username,createdAt,...(username==='private'?{accountPrivate:true}:{}),...(username==='disabled'?{disabled:true}:{})});
data.set('blocked:blocked',['alice']);
data.set('friends:private',['bob']);data.set('friends:bob',['private']);
const alice=await room.createSession('alice'),bob=await room.createSession('bob'),priv=await room.createSession('private');
async function api(path,body,token){const r=await room.api(new Request('https://unit.test'+path,{method:body?'POST':'GET',headers:{'content-type':'application/json'},...(body?{body:JSON.stringify({token,...body})}:{})}));return{status:r.status,data:await r.json()};}
async function drain(){while(tasks.length)await Promise.all(tasks.splice(0));}
async function finish(){let n=0;while([...data.keys()].some(k=>k.startsWith('post-notify:'))){await room.alarm();await drain();assert.ok(++n<50);room=restart();}return n;}
const result=await api('/api/posts',{text:'PRIVATE CAPTION NOT IN PUSH'},alice);assert.equal(result.status,200);const id=result.data.post.id;
assert.equal(packets.length,0,'publish does not synchronously fan out');assert.ok(alarms.length>0);
assert.ok(await finish()>1,'fanout uses durable batches and survives restarts');assert.ok(pages.every(n=>n<=25));
const sent=packets.filter(p=>p.type==='new-post');assert.equal(sent.length,62);assert.ok(!sent.some(p=>['alice','blocked','disabled'].includes(p.to)));
assert.ok(sent.every(p=>p.from==='alice'&&p.fromName==='Name alice'&&p.eventId===`new-post:${id}:${p.to}`));assert.ok(!JSON.stringify(sent).includes('PRIVATE CAPTION'));
for(const p of sent){const notices=data.get('notifications:'+p.to);assert.equal(notices.filter(n=>n.postId===id).length,1);}
await storage.put('post-notify:'+id,{postId:id,cursor:''});await finish();assert.equal(packets.filter(p=>p.type==='new-post').length,62,'retries never send a second event for an existing notification');
const list=await api('/api/notifications?token='+bob);assert.equal(list.status,200);assert.ok(list.data.notifications.some(n=>n.postId===id&&n.fromName==='Name alice'));
let author=data.get('user:alice');author.accountPrivate=true;data.set('user:alice',author);
assert.ok(!(await api('/api/notifications?token='+bob)).data.notifications.some(n=>n.postId===id),'privacy revocation removes old notification visibility');
const before=packets.length;await room.deliverUserPush('bob',{type:'new-post',from:'alice',postId:id,eventId:'late-push'});assert.equal(packets.length,before,'late push rechecks current post permissions');
const privatePost=await api('/api/posts',{text:'friends only'},priv);assert.equal(privatePost.status,200);await finish();assert.deepEqual(packets.slice(before).map(p=>p.to),['bob']);
const hidden=data.get('user:private');hidden.accountHidden=true;data.set('user:private',hidden);
const hiddenPost=await api('/api/posts',{text:'hidden author'},priv);assert.equal(hiddenPost.status,200);const hiddenBefore=packets.length;await finish();assert.deepEqual(packets.slice(hiddenBefore).map(p=>p.to),['bob']);
const missing=await api('/api/posts',{text:'deleted before broadcast'},priv);data.delete('post:'+missing.data.post.id);const missingBefore=packets.length;await finish();assert.equal(packets.length,missingBefore);
console.log('PASS post broadcasts: every eligible account, bounded durable restartable batches, stable IDs, names/links, no duplicates/body leaks, self/block/disabled/private/hidden checks and late deletion/privacy revalidation');
