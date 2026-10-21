import assert from 'node:assert/strict';
import fs from 'node:fs';
const source=fs.readFileSync(new URL('../cloudflare-site/src/worker.js',import.meta.url),'utf8').replace('import { DurableObject } from "cloudflare:workers";','class DurableObject {}');
const {default:worker}=await import('data:text/javascript;charset=utf-8,'+encodeURIComponent(source));
const bytes=Uint8Array.from({length:1000},(_,i)=>i%256),calls=[];
const meta={size:bytes.length,httpEtag:'"fixture"',writeHttpMetadata(h){h.set('content-type','video/mp4')}};
const env={MEDIA_BUCKET:{async head(key){return key==='video/fixture.mp4'?meta:null},async get(key,options){calls.push(options);const r=options?.range;return{...meta,body:r?bytes.slice(r.offset,r.offset+r.length):bytes}}}};
const fetch=(headers={},method='GET',path='video/fixture.mp4')=>worker.fetch(new Request('https://unit.test/media/'+path,{method,headers}),env,{});
let r=await fetch();assert.equal(r.status,200);assert.equal(r.headers.get('content-length'),'1000');assert.equal(r.headers.get('accept-ranges'),'bytes');assert.deepEqual(new Uint8Array(await r.arrayBuffer()),bytes);
for(const [range,start,end] of [['bytes=0-99',0,99],['bytes=900-',900,999],['bytes=-50',950,999],['bytes=-2000',0,999],['bytes=995-5000',995,999]]){
 r=await fetch({range});assert.equal(r.status,206);assert.equal(r.headers.get('content-range'),`bytes ${start}-${end}/1000`);assert.equal(r.headers.get('content-length'),String(end-start+1));assert.deepEqual(new Uint8Array(await r.arrayBuffer()),bytes.slice(start,end+1));
}
for(const range of ['bytes=1000-','bytes=7-2','bytes=-0','bytes=-','bytes=0-3,5-8','nonsense']){r=await fetch({range});assert.equal(r.status,416,range);assert.equal(r.headers.get('content-range'),'bytes */1000');assert.equal((await r.arrayBuffer()).byteLength,0);}
r=await fetch({'range':'bytes=0-9','if-range':'"old"'});assert.equal(r.status,200);assert.equal((await r.arrayBuffer()).byteLength,1000);
r=await fetch({'range':'bytes=0-9','if-range':'"fixture"'});assert.equal(r.status,206);assert.equal((await r.arrayBuffer()).byteLength,10);
const before=calls.length;r=await fetch({},'HEAD');assert.equal(r.status,200);assert.equal(r.headers.get('content-length'),'1000');assert.equal((await r.arrayBuffer()).byteLength,0);assert.equal(calls.length,before,'HEAD never downloads the video');
assert.equal((await fetch({},'GET','video/missing.mp4')).status,404);assert.equal((await fetch({},'POST')).status,405);
console.log('PASS media transport: exact byte ranges, suffix/open/clamped ranges, If-Range, 416, HEAD, missing files and no full-video reads for thumbnails');
