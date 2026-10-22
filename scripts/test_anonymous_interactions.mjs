import assert from 'node:assert/strict';import fs from 'node:fs';import {webcrypto} from 'node:crypto';globalThis.crypto??=webcrypto;
const source=fs.readFileSync(new URL('../cloudflare-site/src/worker.js',import.meta.url),'utf8').replace('import { DurableObject } from "cloudflare:workers";','class DurableObject {}');
const {SignalingRoom}=await import('data:text/javascript;charset=utf-8,'+encodeURIComponent(source));
const state=new Map(),storage={async get(k){return structuredClone(state.get(k))},async put(k,v){state.set(k,structuredClone(v))},async delete(k){state.delete(k)}};
const room=new SignalingRoom({storage,waitUntil:p=>p.catch(()=>{}),getWebSockets:()=>[]},{});
for(const [username,extra] of Object.entries({owner:{fullName:'الاسم الحقيقي'},visitor:{},secret_one:{accountHidden:true,fullName:'الاسم السري',avatar:'/secret.jpg'},secret_two:{accountHidden:true},blocked:{},disabled:{disabled:true}}))await storage.put('user:'+username,{username,...extra});
const tokens={};for(const name of ['owner','visitor','secret_one','secret_two','blocked','disabled'])tokens[name]=await room.createSession(name);
await storage.put('post:old',{id:'old',author:'owner',text:'منشور قديم',likes:[],reactions:{},comments:[]});await storage.put('postIds',[]);
async function req(path,actor,body){const token=tokens[actor];const r=await room.api(new Request('https://test.invalid'+path+(body?'':(path.includes('?')?'&':'?')+'token='+token),{method:body?'POST':'GET',...(body?{headers:{'content-type':'application/json'},body:JSON.stringify({...body,token})}:{})}));return{status:r.status,data:await r.json()};}
for(const actor of ['secret_one','secret_two'])assert.equal((await req('/api/post-react',actor,{postId:'old',reaction:'love'})).status,200);
assert.equal((await req('/api/post-comment','secret_one',{postId:'old',text:'تعليق مجهول'})).status,200);
const comment=(await storage.get('post:old')).comments[0];assert.equal((await req('/api/comment-reply','secret_two',{postId:'old',commentId:comment.id,text:'رد مجهول'})).status,200);
const noLeak=value=>{const text=JSON.stringify(value);for(const secret of ['secret_one','secret_two','الاسم السري','/secret.jpg'])assert.ok(!text.includes(secret),'identity leaked: '+secret)};
for(const viewer of ['owner','visitor']){
 const people=(await req('/api/post-interactions?postId=old',viewer)).data;assert.equal(people.reactionCount,2);assert.equal(people.people.length,2);assert.equal(new Set(people.people.map(p=>p.username)).size,2);assert.ok(people.people.every(p=>p.anonymous&&p.fullName==='حساب مخفي'));noLeak(people);
 const comments=(await req('/api/post-interactions?postId=old&kind=comments',viewer)).data;assert.equal(comments.comments[0].authorInfo.fullName,'حساب مخفي');assert.equal(comments.comments[0].replies[0].authorInfo.fullName,'حساب مخفي');noLeak(comments);
 const target=await req('/api/post?postId=old',viewer);assert.equal(target.status,200,'old post opens independently of recent feed');noLeak(target.data);
}
await storage.put('friends:owner',['secret_one']);await storage.put('friends:secret_one',['owner']);noLeak((await req('/api/post-interactions?postId=old','owner')).data);
assert.equal((await req('/api/post?postId=old','secret_one')).data.post.myReaction,'love','hidden account retains its own selected reaction');
let notices=(await req('/api/notifications','owner')).data.notifications;assert.ok(notices.length>=3);assert.ok(notices.every(n=>n.from===''&&n.fromName==='حساب مخفي'));noLeak(notices);
await req('/api/notifications/read-one','owner',{notificationId:notices[0].id});assert.ok((await req('/api/notifications','owner')).data.notifications[0].read);
const pushed=[];room.sendDevicePush=async(to,data)=>pushed.push({to,...data});await room.deliverUserPush('owner',{type:'reaction',from:'secret_one',eventId:'test-event',createdAt:1});noLeak(pushed);assert.equal(pushed[0].fromName,'حساب مخفي');
await room.deliverUserPush('visitor',{type:'friend-accepted',from:'owner',eventId:'test-event',createdAt:1});assert.equal(pushed[1].fromName,'الاسم الحقيقي');
await storage.put('blocked:owner',['secret_one']);assert.equal((await req('/api/post-react','secret_one',{postId:'old',reaction:'wow'})).status,403);assert.equal((await req('/api/post-interactions?postId=old','owner')).data.reactionCount,1);
await storage.put('post:private',{id:'private',author:'secret_two',likes:[],comments:[]});assert.equal((await req('/api/post?postId=private','visitor')).status,404);
const post=await storage.get('post:old');post.archived=true;await storage.put('post:old',post);assert.equal((await req('/api/post?postId=old','visitor')).status,404);
console.log('PASS hidden interactions: two distinct anonymous counts, comments/replies, friends/strangers, self selection, notification and push masking, real sender name, old-post destination, blocking/private/archive authorization');
