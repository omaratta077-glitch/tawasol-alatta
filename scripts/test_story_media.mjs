import assert from 'node:assert/strict';
import fs from 'node:fs';
import {webcrypto} from 'node:crypto';
globalThis.crypto ??= webcrypto;
const source=fs.readFileSync(new URL('../cloudflare-site/src/worker.js',import.meta.url),'utf8').replace('import { DurableObject } from "cloudflare:workers";','class DurableObject {}');
const {SignalingRoom}=await import('data:text/javascript;charset=utf-8,'+encodeURIComponent(source));
const data=new Map();
const room=new SignalingRoom({storage:{async get(k){return structuredClone(data.get(k))},async put(k,v){data.set(k,structuredClone(v))},async delete(k){data.delete(k)}},getWebSockets:()=>[]},{});
data.set('user:alice',{username:'alice',fullName:'Alice'});
const token=await room.createSession('alice');
async function post(body){const response=await room.api(new Request('https://test/api/stories',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify(body)}));return {status:response.status,body:await response.json()};}
assert.equal((await post({text:'unauthorized'})).status,401);
assert.equal((await post({token})).status,400);
for(const content of [{text:'text only'},{media:'/media/photo.webp',mediaType:'image'},{media:'/media/movie.webm',mediaType:'video',text:'caption'}]){
 const result=await post({token,...content});assert.equal(result.status,200);
 const stored=data.get('story:'+result.body.story.id);
 assert.equal(stored.author,'alice');assert.equal(stored.media,content.media||'');assert.equal(stored.mediaType,content.mediaType||'');
 assert.equal(stored.expiresAt-stored.createdAt,86400000);
}
console.log('PASS authenticated text/image/video stories preserve media type, caption and expiry');
