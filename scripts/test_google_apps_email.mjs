import assert from "node:assert/strict";
import {sendViaGoogleAppsScript} from "../cloudflare-site/src/google-apps-email.mjs";
const url="https://script.google.com/macros/s/AKfycbz20fN_J2cs0feEHqT-lxZ3Bznk51NWu6s3buRAI5X3D8u3QpA6lAqTdn71c2rN_OjFwA/exec";
const env={GAS_EMAIL_WEBAPP_URL:url,GAS_EMAIL_API_SECRET:"unit-test-secret"};
const calls=[];
const mock=async(u,options)=>{
  calls.push({url:u,options});
  return new Response(JSON.stringify({ok:true}),{status:200,headers:{"content-type":"application/json"}});
};
for(const type of ["verify","reset","admin"]){
  assert.equal(await sendViaGoogleAppsScript(env,"person@example.com","012345",type,mock),true);
  const last=calls.at(-1);
  assert.equal(last.url,url);
  assert.equal(last.options.method,"POST");
  assert.equal(last.options.redirect,"follow");
  assert.match(last.options.headers["Content-Type"],/^text\\/plain/);
  assert.deepEqual(JSON.parse(last.options.body),{secret:"unit-test-secret",email:"person@example.com",code:"012345",type});
}
await assert.rejects(sendViaGoogleAppsScript({...env,GAS_EMAIL_API_SECRET:""},"person@example.com","123456","verify",mock),/EMAIL_PROVIDER_NOT_CONFIGURED/);
await assert.rejects(sendViaGoogleAppsScript({...env,GAS_EMAIL_WEBAPP_URL:"https://example.org/exec"},"person@example.com","123456","verify",mock),/EMAIL_PROVIDER_NOT_CONFIGURED/);
await assert.rejects(sendViaGoogleAppsScript(env,"bad-email","123456","verify",mock),/EMAIL_SEND_FAILED/);
await assert.rejects(sendViaGoogleAppsScript(env,"person@example.com","12345","verify",mock),/EMAIL_SEND_FAILED/);
await assert.rejects(sendViaGoogleAppsScript(env,"person@example.com","123456","verify",async()=>new Response(JSON.stringify({ok:false,error:"Unauthorized"}),{status:200})),/EMAIL_SEND_FAILED/);
await assert.rejects(sendViaGoogleAppsScript(env,"person@example.com","123456","verify",async()=>new Response("<html>login</html>",{status:200})),/EMAIL_SEND_FAILED/);
await assert.rejects(sendViaGoogleAppsScript(env,"person@example.com","123456","verify",async()=>{throw Error("timeout");}),/EMAIL_SEND_FAILED/);
console.log("PASS: Google Apps Script email transport and failure handling");
