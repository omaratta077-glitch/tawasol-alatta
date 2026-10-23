import assert from 'node:assert/strict';
import fs from 'node:fs';
import {webcrypto} from 'node:crypto';
globalThis.crypto??=webcrypto;
const source=fs.readFileSync(new URL('../cloudflare-site/src/worker.js',import.meta.url),'utf8').replace('import { DurableObject } from "cloudflare:workers";','class DurableObject {}');
const {SignalingRoom}=await import('data:text/javascript;charset=utf-8,'+encodeURIComponent(source));
const endpoint='https://script.google.com/macros/s/test-owner-email/exec';
const originalFetch=globalThis.fetch;
async function scenario({google='reject',resend='ok',quota=false,secondary=true}={}){
 const data=new Map(),requests=[];
 const storage={async get(k){return structuredClone(data.get(k))},async put(k,v){data.set(k,structuredClone(v))},async delete(k){data.delete(k)}};
 const room=new SignalingRoom({storage,getWebSockets:()=>[],waitUntil(){}},{SUPER_ADMIN_USERNAME:'owner',SUPER_ADMIN_EMAIL:'owner@example.test',GAS_EMAIL_WEBAPP_URL:endpoint,GAS_EMAIL_API_SECRET:'private-google-key',...(secondary?{RESEND_API_KEY:'re_private-secondary-key'}:{})});
 for(const username of ['owner','other'])data.set('user:'+username,{username,email:username+'@example.test'});
 if(quota)data.set('google-email-quota',{day:new Date().toISOString().slice(0,10),dayCount:80});
 const token=await room.createSession('owner'),other=await room.createSession('other');
 globalThis.fetch=async(url,opts)=>{
  const payload=JSON.parse(opts.body);requests.push({url:String(url),payload});
  if(String(url)===endpoint){
   if(google==='timeout')throw Error('timeout');
   return Response.json(google==='ok'?{ok:true}:{ok:false,error:google==='unauthorized'?'Unauthorized':google==='sendfailed'?'Send failed':'provider-internal-detail'});
  }
  assert.equal(String(url),'https://api.resend.com/emails');
  assert.deepEqual(payload.to,['owner@example.test'],'fallback cannot redirect a code to another recipient');
  return Response.json(resend==='ok'?{id:'accepted'}:{name:'provider-rejected',message:'provider-internal-detail'},{status:resend==='ok'?200:resend==='quota'?429:403});
 };
 async function api(path,body={},session=token){const r=await room.api(new Request('https://unit.test'+path,{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({token:session,...body})}));return {status:r.status,data:await r.json()};}
 return {data,room,requests,api,other,token};
}
try{
 let f=await scenario({google:'ok'}),r=await f.api('/api/admin/security/code');
 assert.equal(r.status,200);assert.equal(f.requests.length,1,'working primary is not duplicated');
 f=await scenario();
 assert.equal((await f.api('/api/admin/security/code',{},f.other)).status,403);assert.equal(f.requests.length,0,'only the verified owner can request mail');
 r=await f.api('/api/admin/security/code');assert.equal(r.status,200);assert.equal(r.data.email,'ow***@example.test');
 assert.equal(f.requests.length,2);
 const code=f.requests[0].payload.code;
 assert.ok(f.requests[1].payload.html.includes(code),'secondary uses the same code');
 const proof=f.data.get('admin-security-code:owner');assert.ok(proof.hash&&proof.tokenHash);assert.ok(!JSON.stringify(proof).includes(code));
 assert.equal((await f.api('/api/admin/security/code')).status,429,'accepted mail keeps resend throttling');
 const otherOwnerSession=await f.room.createSession('owner');
 const save={code,newPassword:'owner-protection-123',confirmPassword:'owner-protection-123'};
 assert.equal((await f.api('/api/admin/security/password',save,otherOwnerSession)).status,403,'mail proof remains tied to the requesting session');
 assert.equal((await f.api('/api/admin/security/password',save)).status,200,'secondary-delivered code enrolls the owner');
 assert.ok(f.data.get('admin-security:owner').hash);assert.ok(!f.data.has('admin-security-code:owner'));
 f=await scenario({quota:true});r=await f.api('/api/admin/security/code');assert.equal(r.status,200);assert.equal(f.requests.length,1,'local Gmail cap still permits configured secondary');
 f=await scenario({google:'timeout'});assert.equal((await f.api('/api/admin/security/code')).status,200,'network failure can use secondary');
 for(const mode of ['reject','quota']){
  f=await scenario({resend:mode});f.data.set('admin-security:owner',{hash:'existing-hash'});
  r=await f.api('/api/admin/security/code');assert.equal(r.status,mode==='quota'?429:503);
  assert.equal(r.data.code,mode==='quota'?'ADMIN_EMAIL_RATE_LIMITED':'ADMIN_EMAIL_REJECTED');
  assert.match(r.data.error,/تعذر إرسال كود الحماية/);assert.ok(!JSON.stringify(r.data).includes('private-'));assert.ok(!JSON.stringify(r.data).includes('provider-internal-detail'));
  assert.ok(!f.data.has('admin-security-code:owner'),'rejected mail never creates a usable proof');assert.equal(f.data.get('admin-security:owner').hash,'existing-hash');
 }
 for(const [mode,expected,fragment] of [['unauthorized','GOOGLE_EMAIL_SECRET_MISMATCH','GAS_EMAIL_API_SECRET'],['sendfailed','GOOGLE_EMAIL_SEND_FAILED','Gmail']]){
  f=await scenario({google:mode,resend:'reject'});r=await f.api('/api/admin/security/code');
  assert.equal(r.status,503);assert.equal(r.data.code,expected);assert.equal(r.data.error,'تعذر إرسال كود الحماية. حاول مرة أخرى');assert.ok(!r.data.error.includes(fragment));
  assert.equal(f.requests.length,2);assert.ok(!f.data.has('admin-security-code:owner'));
  assert.ok(!JSON.stringify(r.data).includes('private-'));assert.ok(!JSON.stringify(r.data).includes(f.requests[0].payload.code));
 }
 f=await scenario({secondary:false});assert.equal((await f.api('/api/admin/security/code')).status,503);assert.equal(f.requests.length,1);
 f=await scenario();await assert.rejects(f.room.sendVerificationEmail('person@example.test','123456'),/EMAIL_SEND_FAILED/);assert.equal(f.requests.length,1,'ordinary signup transport behavior is unchanged');
 console.log('PASS owner code delivery: primary/secondary failover, quota/network failures, fixed recipient, session-bound proof, throttling, no proof on rejection, existing password retained and safe actionable errors');
}finally{globalThis.fetch=originalFetch;}
