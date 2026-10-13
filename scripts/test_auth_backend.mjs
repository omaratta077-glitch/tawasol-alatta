import fs from 'node:fs';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
const source = fs.readFileSync(process.argv[2] || new URL('../worker.js', import.meta.url),'utf8')
    .replace('import { DurableObject } from "cloudflare:workers";', 'class DurableObject {}');
const mod = await import('data:text/javascript;charset=utf-8,' + encodeURIComponent(source));
const storageMap = new Map();
const storage = {
    async get(k) { return storageMap.get(k); },
    async put(k,v) { storageMap.set(k,v); },
    async delete(k) { storageMap.delete(k); },
};
const room = new mod.SignalingRoom({storage}, {RESEND_API_KEY:'re_test', VERIFY_FROM_EMAIL:'Tawasol <test@example.com>', GOOGLE_CLIENT_ID:'web-test-client.apps.googleusercontent.com'});
const encoder=new TextEncoder();
async function sha(s) {return Array.from(new Uint8Array(await crypto.webcrypto.subtle.digest('SHA-256',encoder.encode(s))), b=>b.toString(16).padStart(2,'0')).join('');}
let verificationCode='';
globalThis.fetch=async (url, opts) => {
 if(String(url).startsWith('https://api.resend.com')){
    const data=JSON.parse(opts.body);
    verificationCode=data.html.match(/letter-spacing:9px[^>]*>(\d{6})</)?.[1]||'';
    return new Response(JSON.stringify({id:'test'}),{status:200});
 }
 if(String(url).startsWith('https://oauth2.googleapis.com/tokeninfo')){
    return new Response(JSON.stringify({iss:'accounts.google.com',aud:'web-test-client.apps.googleusercontent.com',email_verified:'true',email:'newuser@example.com',name:'Google Person',sub:'uniqueGoogleUser',exp:Math.floor(Date.now()/1000)+1800}),{status:200});
 }
 throw new Error('Unexpected fetch '+url);
};
async function post(route,obj){ const response=await room.api(new Request('https://example.com'+route,{method:'POST',headers:{'content-type':'application/json','CF-Connecting-IP':'10.2.3.4'},body:JSON.stringify(obj)}));return {status:response.status,data:await response.json()};}
await storage.put('user:tester',{username:'tester', email:'tester@example.com', passwordHash:await sha('old-password')});
await storage.put('usernames',['tester']);
const priorSession=await room.createSession('tester');
let r=await post('/api/password-reset-request',{email:'tester@example.com'});
assert.equal(r.status,200);
assert.ok(r.data.requestId);
assert.match(verificationCode,/^\d{6}$/);
let invalid=await post('/api/password-reset-confirm',{requestId:r.data.requestId,code:'000000',newPassword:'strong-password',confirmPassword:'strong-password'});
assert.equal(invalid.status,400);
let ok=await post('/api/password-reset-confirm',{requestId:r.data.requestId,code:verificationCode,newPassword:'strong-password',confirmPassword:'strong-password'});
assert.equal(ok.status,200);
assert.equal(ok.data.ok,true);
assert.equal(await room.session(priorSession),null);
assert.equal((await post('/api/login',{username:'tester',password:'old-password'})).status,401);
assert.equal((await post('/api/login',{username:'tester',password:'strong-password'})).status,200);
assert.notEqual((await post('/api/password-reset-confirm',{requestId:r.data.requestId,code:verificationCode,newPassword:'xPassword123',confirmPassword:'xPassword123'})).status,200);
const sessionForChange=await room.createSession('tester');
assert.equal((await post('/api/change-password',{token:sessionForChange,oldPassword:'no',newPassword:'last-password',confirmPassword:'last-password'})).status,401);
assert.equal((await post('/api/change-password',{token:sessionForChange,oldPassword:'strong-password',newPassword:'last-password',confirmPassword:'last-password'})).status,200);
assert.equal(await room.session(sessionForChange),null);
assert.equal((await post('/api/login',{username:'tester',password:'last-password'})).status,200);
let googleFail=await post('/api/google-login',{credential:'a.b.c',signupFlow:'android-signup',fullName:'Test User',age:0});
assert.equal(googleFail.status,400);
let googlePass=await post('/api/google-login',{credential:'a.b.c',signupFlow:'android-signup',fullName:'New Display Name',age:23,city:'Minya'});
assert.equal(googlePass.status,200);
assert.equal(googlePass.data.ok,true);
const user=await room.user(googlePass.data.user.username);
assert.equal(user.fullName,'New Display Name');
assert.equal(user.city,'Minya');
assert.equal(user.age,23);
console.log('PASS: password reset, change-password, one-time code, invalidated sessions, Google account signup');
