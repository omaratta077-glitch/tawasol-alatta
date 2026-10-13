import fs from 'node:fs';
import vm from 'node:vm';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';

const source=fs.readFileSync(process.argv[2]||'cloudflare-site/src/worker.js','utf8');
assert(source.includes('ALATTA_ANDROID_GOOGLE_BRIDGE_V104'));
let code=source.replace(/^import .*?;\s*$/gm,'').replace(/export class SignalingRoom/g,'class SignalingRoom').replace(/export default\s*\{/g,'const exportedWorker = {');
code+='\nglobalThis.__TestRoom=SignalingRoom;';
const env={DurableObject:class {},crypto:crypto.webcrypto,TextEncoder,TextDecoder,Request,Response,Headers,URL,btoa,console,setTimeout,clearTimeout};
vm.runInNewContext(code,env,{timeout:5000});
const store=new Map();
const ctx={storage:{
 async get(k){return store.get(k)},async put(k,v){store.set(k,v)},async delete(k){store.delete(k)},
}};
const room=new env.__TestRoom(ctx,{GOOGLE_CLIENT_ID:'web-client-id'});
const origin='https://tawasol-alatta.omaratta077-4fd.workers.dev';
const verifier=crypto.randomBytes(32).toString('base64url');
const challenge=crypto.createHash('sha256').update(verifier).digest('base64url');
const token='x'.repeat(160);
const post=async(path,data,withOrigin=true)=>{
 const headers={'Content-Type':'application/json'};
 if(withOrigin)headers.Origin=origin;
 const r=await room.api(new Request(origin+path,{method:'POST',headers,body:JSON.stringify(data)}));
 return {status:r.status,body:await r.json()};
};
let a=await post('/api/android-google-bridge/start',{challenge,credential:token},false);
assert.equal(a.status,403,'Browser origin guard');
a=await post('/api/android-google-bridge/start',{challenge,credential:token});
assert.equal(a.status,200,'Start handoff');
const code1=a.body.code;assert.match(code1,/^[a-f0-9]{64}$/);
let wrong=await post('/api/android-google-bridge/exchange',{code:code1,verifier:crypto.randomBytes(32).toString('base64url')});
assert.equal(wrong.status,403,'PKCE prevents intercepted redirect');
let result=await post('/api/android-google-bridge/exchange',{code:code1,verifier});
assert.equal(result.status,200,'Exchange works');assert.equal(result.body.credential,token);
result=await post('/api/android-google-bridge/exchange',{code:code1,verifier});
assert.equal(result.status,410,'Single-use code');
console.log('Google bridge: origin, PKCE, one-time exchange tests passed.');
