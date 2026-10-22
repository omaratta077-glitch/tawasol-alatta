import assert from 'node:assert/strict';
import fs from 'node:fs';
import {webcrypto} from 'node:crypto';
if (!globalThis.crypto)globalThis.crypto=webcrypto;
const original=fs.readFileSync(new URL('../cloudflare-site/src/worker.js',import.meta.url),'utf8')
  .replace('import { DurableObject } from "cloudflare:workers";','class DurableObject {}');
const {SignalingRoom}=await import('data:text/javascript;charset=utf-8,'+encodeURIComponent(original));
const storage={async get(){return undefined},async put(){},async delete(){}};
const config={BREVO_API_KEY:'xkeysib-fake-test-key',BREVO_SENDER_EMAIL:'verified@example.test',
  BREVO_SENDER_NAME:'تواصل العطا',RESEND_API_KEY:'re_fallback_fake'};
const room=new SignalingRoom({storage},config);
const sent=[];
const realFetch=globalThis.fetch;
globalThis.fetch=async(url,opts)=>{
  sent.push({url:String(url),opts,body:JSON.parse(opts.body)});
  return new Response(JSON.stringify({messageId:'test-1'}),{status:201});
};
try {
  await room.sendVerificationEmail('member@example.test','654321');
  assert.equal(sent.length,1);
  assert.equal(sent[0].url,'https://api.brevo.com/v3/smtp/email');
  assert.equal(sent[0].opts.headers['api-key'],config.BREVO_API_KEY);
  assert.equal(sent[0].body.sender.email,'verified@example.test');
  assert.deepEqual(sent[0].body.to,[{email:'member@example.test'}]);
  assert.equal(sent[0].body.subject,'كود تأكيد حساب تواصل العطا');
  assert.ok(sent[0].body.htmlContent.includes('654321'));
  await room.sendVerificationEmail('admin@example.test','987654',true);
  assert.equal(sent[1].body.subject,'حماية إدارة تواصل العطا');
  await room.sendPasswordResetEmail('member@example.test','123456');
  assert.equal(sent[2].body.subject,'استعادة كلمة المرور — تواصل العطا');
  assert.ok(sent[2].body.htmlContent.includes('123456'));
  assert.equal(sent.length,3,'one request per code, no extra provider sends');

  delete config.BREVO_SENDER_EMAIL;
  await assert.rejects(room.sendVerificationEmail('member@example.test','444444'),
    e=>e.message==='BREVO_SENDER_NOT_CONFIGURED');
  assert.equal(sent.length,3,'no send before sender address is configured');
  config.BREVO_SENDER_EMAIL='verified@example.test';
  globalThis.fetch=async()=>new Response('not authorized',{status:401});
  await assert.rejects(room.sendVerificationEmail('member@example.test','333333'),
    e=>e.message==='EMAIL_SEND_FAILED'&&e.providerName==='Brevo'&&e.providerStatus===401);
  assert.equal(sent.length,3,'no fallback on Brevo failure to avoid sending twice');
  delete config.BREVO_API_KEY;
  globalThis.fetch=async(url,opts)=>{
    sent.push({url:String(url),body:JSON.parse(opts.body)});
    return new Response('{"id":"resend-test"}',{status:200});
  };
  await room.sendVerificationEmail('member@example.test','112233');
  assert.equal(sent.at(-1).url,'https://api.resend.com/emails',
    'pre-existing Resend remains functional if Brevo is not enabled');
  delete config.RESEND_API_KEY;
  await assert.rejects(room.sendVerificationEmail('member@example.test','112233'),
    e=>e.message==='EMAIL_PROVIDER_NOT_CONFIGURED');
  console.log('PASS Brevo verification/admin/reset mail, sender safety, no double sends, Resend fallback');
} finally {globalThis.fetch=realFetch;}
