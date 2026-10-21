import assert from 'node:assert/strict';import fs from 'node:fs';import{webcrypto}from'node:crypto';globalThis.crypto??=webcrypto;
const code=fs.readFileSync(new URL('../cloudflare-site/src/worker.js',import.meta.url),'utf8').replace('import { DurableObject } from "cloudflare:workers";','class DurableObject {}');
const{SignalingRoom}=await import('data:text/javascript;charset=utf-8,'+encodeURIComponent(code));
const data=new Map(),reads=[];
const storage={async get(k){reads.push(k);return structuredClone(data.get(k))},async put(k,v){data.set(k,structuredClone(v))},async delete(k){data.delete(k)}};
const room=new SignalingRoom({storage,getWebSockets:()=>[]},{});
for(const name of ['alice','bob','private','blocked'])data.set('user:'+name,{username:name,fullName:name,cover:'UNNEEDED_COVER',avatar:'/media/image/'+name+'.jpg',accountPrivate:name==='private'});
data.set('blocked:alice',['blocked']);
const token=await room.createSession('alice');
const ids=[];
for(let i=0;i<120;i++){
 const id='post'+i;ids.push(id);
 data.set('post:'+id,{id,author:i%13===0?'private':i%17===0?'blocked':i%2?'bob':'alice',text:'post '+i,archived:i%19===0,likes:[],comments:[]});
}
data.set('postIds',ids);
async function get(extra='',auth=true){const r=await room.api(new Request('https://unit.test/api/posts?page=1&limit=10'+(auth?'&token='+token:'')+extra));return{status:r.status,body:await r.json()}}
assert.equal((await get('',false)).status,401);
reads.length=0;const first=await get();assert.equal(first.status,200);assert.equal(first.body.posts.length,10);assert.equal(first.body.paged,true);assert.ok(first.body.nextCursor);
assert.ok(reads.filter(k=>k.startsWith('post:')).length<25,'first screen does not load all post histories');
assert.ok(!JSON.stringify(first.body).includes('UNNEEDED_COVER'));
const collected=[];let cursor='',pages=0;
do{const r=await get(cursor?'&before='+encodeURIComponent(cursor):'');assert.equal(r.status,200);collected.push(...r.body.posts);cursor=r.body.nextCursor;pages++;assert.ok(pages<20)}while(cursor);
assert.equal(new Set(collected.map(p=>p.id)).size,collected.length);
assert.ok(collected.length>50,'older posts remain accessible beyond the old 40-post window');
assert.ok(collected.every(p=>!['private','blocked'].includes(p.author)&&!p.archived));
const own=await get('&author=alice');assert.ok(own.body.posts.length>0&&own.body.posts.every(p=>p.author==='alice'));
assert.equal((await get('&before=invalid')).status,409);
data.set('postIds',[...ids,'new-post']);assert.equal((await get('&before='+first.body.nextCursor)).status,409,'a changed timeline cannot silently duplicate/skip posts');
// A page of entirely hidden/archived posts must still offer a private, opaque cursor.
data.set('postIds',Array.from({length:100},(_,i)=>'secret'+i));for(let i=0;i<100;i++)data.set('post:secret'+i,{id:'secret'+i,author:'private',text:'SECRET'});
const hidden=await get();assert.deepEqual(hidden.body.posts,[]);assert.ok(hidden.body.nextCursor);assert.ok(!JSON.stringify(hidden.body).includes('secret')&&!JSON.stringify(hidden.body).includes('SECRET'));
console.log('PASS paged feed: bounded first page, older posts, own-author filter, no duplicate/skip, compact authors, session/privacy/block checks, opaque cursors and changed-timeline detection');
