import assert from 'node:assert/strict';
import fs from 'node:fs';
import {webcrypto} from 'node:crypto';
globalThis.crypto ??= webcrypto;
const source=fs.readFileSync(new URL('../cloudflare-site/src/worker.js',import.meta.url),'utf8').replace('import { DurableObject } from "cloudflare:workers";','class DurableObject {}');
const {default:worker,SignalingRoom}=await import('data:text/javascript;charset=utf-8,'+encodeURIComponent(source));
const data=new Map(),reads=[],uploads=[];
const storage={async get(k){reads.push(k);return structuredClone(data.get(k))},async put(k,v){data.set(k,structuredClone(v))},async delete(k){return data.delete(k)}};
const bucket={async put(key,body,options){let bytes=0;for await(const chunk of body)bytes+=chunk.byteLength;uploads.push({key,options,bytes});return{size:bytes}}};
const room=new SignalingRoom({storage,getWebSockets:()=>[],waitUntil:p=>p.catch(()=>{})},{MEDIA_BUCKET:bucket});
for(const username of ['alice','bob','carol','dave'])data.set('user:'+username,{username,email:username+'@test.example',passwordHash:'private',avatar:'data:image/jpeg;base64,AA==',cover:'unnecessary-cover'});
const token=await room.createSession('alice');
const api=(path,body,headers={})=>room.api(new Request('https://unit.test'+path,{method:'POST',headers:{'content-type':'application/json',...headers},body:typeof body==='string'?body:JSON.stringify(body)}));
const upload=(type,kind='image')=>api('/api/media-upload?token='+token+'&name=fixture.svg&kind='+kind,'<svg xmlns="http://www.w3.org/2000/svg"><script>alert(localStorage.token)</script></svg>',{'content-type':type});
for(const type of ['image/svg+xml','text/html','application/xhtml+xml','image/fake'])assert.equal((await upload(type)).status,415);
assert.equal(uploads.length,0,'active images never reach storage');
assert.equal((await upload('image/png')).status,200,'raster images still work');
assert.equal((await upload('video/mp4','video')).status,200,'video uploads still work');
assert.equal((await upload('audio/ogg','audio')).status,200,'audio uploads still work');
for(const body of ['{broken','null','[]','"text"','17'])assert.equal((await api('/api/login',body)).status,400);
assert.equal((await api('/api/login','{}',{'content-length':String(4*1024*1024+1)})).status,413);
let cancelled=false,produced=0;
const stream=new ReadableStream({pull(c){produced++;c.enqueue(new Uint8Array(64*1024));},cancel(){cancelled=true;}});
const oversized=await room.api(new Request('https://unit.test/api/login',{method:'POST',body:stream,duplex:'half'}));
assert.equal(oversized.status,413);assert.ok(cancelled);assert.ok(produced<=67,'rejects before buffering the unbounded request');
assert.equal(oversized.headers.get('cache-control'),'no-store');
// A blocked login must not scan every account before returning its rate limit.
data.set('login-fail:missing@test.example',{count:12,until:Date.now()+60000});reads.length=0;
assert.equal((await api('/api/login',{username:'missing@test.example',password:'x'})).status,429);
assert.ok(!reads.includes('usernames') && !reads.some(k=>k.startsWith('user:')));
const closed=[];const ws={close:(...args)=>closed.push(args),deserializeAttachment:()=>({user:'alice',token}),send(){}};
await room.handleSocketMessage(ws,'x'.repeat(4*1024*1024+1));assert.equal(closed[0][0],1009);
for(const msg of ['null','[]','false'])await room.handleSocketMessage(ws,msg);
// Previously uploaded SVGs are sandboxed even on partial or HEAD responses.
const bytes=new TextEncoder().encode('<svg><script>alert(1)</script></svg>');
let type='image/svg+xml';
const meta={size:bytes.length,httpEtag:'"fixture"',writeHttpMetadata(h){h.set('content-type',type)}};
const env={MEDIA_BUCKET:{async head(){return meta},async get(key,options){const r=options?.range;return{...meta,body:r?bytes.slice(r.offset,r.offset+r.length):bytes}}}};
for(const [method,headers,status] of [['GET',{},200],['HEAD',{},200],['GET',{range:'bytes=0-5'},206],['GET',{range:'bytes=900-'},416]]){
 const r=await worker.fetch(new Request('https://unit.test/media/image/old.svg',{method,headers}),env);
 assert.equal(r.status,status);assert.equal(r.headers.get('x-content-type-options'),'nosniff');
 assert.equal(r.headers.get('content-disposition'),'attachment');assert.match(r.headers.get('content-security-policy'),/sandbox/);
}
for(type of ['image/png','video/mp4','audio/ogg','application/pdf']){
 const r=await worker.fetch(new Request('https://unit.test/media/image/fixture'),env);
 assert.equal(r.status,200);assert.equal(r.headers.get('content-disposition'),null);assert.equal(r.headers.get('x-content-type-options'),'nosniff');
}
console.log('PASS audit security: rejects active-image uploads; sandboxes legacy content; bounds streamed JSON/WS; validates JSON shape; rate-limits before account scans; preserves raster/video/audio/PDF and byte ranges');
