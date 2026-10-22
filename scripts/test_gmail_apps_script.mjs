import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {webcrypto,createHash} from 'node:crypto';
if (!globalThis.crypto)globalThis.crypto=webcrypto;
const workerSource=fs.readFileSync(new URL('../cloudflare-site/src/worker.js',import.meta.url),'utf8')
  .replace('import { DurableObject } from "cloudflare:workers";','class DurableObject {}');
const {SignalingRoom}=await import('data:text/javascript;charset=utf-8,'+encodeURIComponent(workerSource));
const script=fs.readFileSync(new URL('../integrations/gmail-apps-script/Code.gs',import.meta.url),'utf8');
const delivered=[];
const secret='test-only-shared-secret-at-least-32-characters';
const apps={
  ContentService:{MimeType:{JSON:'application/json'},createTextOutput(data){
    return {value:data,setMimeType(){return this;}};
  }},
  PropertiesService:{getScriptProperties(){return {getProperty(){return secret}}}},
  Utilities:{DigestAlgorithm:{SHA_256:'SHA_256'},computeDigest(_mode,s){
    return [...createHash('sha256').update(s).digest()].map(x=>x>127?x-256:x);
  }},
  MailApp:{getRemainingDailyQuota(){return 100},sendEmail(mail){delivered.push(mail)}}
};
vm.createContext(apps);vm.runInContext(script,apps);
const invoke=(body)=>JSON.parse(apps.doPost({postData:{contents:JSON.stringify(body)}}).value);
assert.equal(invoke({secret:'wrong',email:'user@example.com',code:'123456',purpose:'signup'}).ok,false);
assert.equal(invoke({secret,email:'user@example.com',code:'12345',purpose:'signup'}).ok,false);
assert.equal(invoke({secret,email:'user@example.com',code:'123456',purpose:'bad'}).ok,false);
assert.equal(delivered.length,0);
assert.equal(invoke({secret,email:'user@example.com',code:'123456',purpose:'signup'}).ok,true);
assert.equal(delivered[0].to,'user@example.com');
assert.equal(delivered[0].subject,'كود تأكيد حساب تواصل العطا');
assert.ok(delivered[0].htmlBody.includes('123456'));
assert.equal(invoke({secret,email:'admin@example.com',code:'987654',purpose:'admin'}).ok,true);
assert.equal(invoke({secret,email:'member@example.com',code:'654321',purpose:'reset'}).ok,true);
assert.equal(delivered.length,3);

const env={ATTA_GMAIL_SCRIPT_URL:'https://script.google.com/macros/s/TEST_ID/exec',
           ATTA_GMAIL_SHARED_SECRET:secret,RESEND_API_KEY:'re_old'};
const room=new SignalingRoom({storage:{async get(){},async put(){}}},env);
const realFetch=globalThis.fetch;
const sent=[];
globalThis.fetch=async(url,opts)=>{
  sent.push({url:String(url),request:JSON.parse(opts.body)});
  return new Response(JSON.stringify({ok:true}),{status:200});
};
try{
  await room.sendVerificationEmail('user@example.com','123456');
  await room.sendVerificationEmail('admin@example.com','987654',true);
  await room.sendPasswordResetEmail('user@example.com','654321');
  assert.equal(sent.length,3,'only Gmail, no duplicate Resend requests');
  assert.deepEqual(sent.map(x=>x.request.purpose),['signup','admin','reset']);
  assert.ok(sent.every(x=>x.url===env.ATTA_GMAIL_SCRIPT_URL));
  assert.ok(sent.every(x=>x.request.secret===secret));
  globalThis.fetch=async()=>new Response('<html>Google login required</html>',{status:200});
  await assert.rejects(room.sendVerificationEmail('user@example.com','111111'),
    e=>e.message==='EMAIL_SEND_FAILED');
  env.ATTA_GMAIL_SCRIPT_URL='';
  env.ATTA_GMAIL_SHARED_SECRET='';
  globalThis.fetch=async(url,opts)=>{
    sent.push({url:String(url),request:JSON.parse(opts.body)});
    return new Response('{}',{status:200});
  };
  await room.sendVerificationEmail('user@example.com','222222');
  assert.equal(sent.at(-1).url,'https://api.resend.com/emails',
    'existing Resend path retained when Gmail not configured');
  env.ATTA_GMAIL_SCRIPT_URL='https://evil.example.com/webhook';
  env.ATTA_GMAIL_SHARED_SECRET=secret;
  await assert.rejects(room.sendVerificationEmail('user@example.com','333333'),
    e=>e.message==='EMAIL_PROVIDER_NOT_CONFIGURED');
  console.log('PASS Gmail script OTP, secret validation, no double send, reset/admin, Resend backup');
}finally{globalThis.fetch=realFetch;}
