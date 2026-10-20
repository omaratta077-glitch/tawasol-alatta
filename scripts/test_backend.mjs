import fs from 'node:fs';
import assert from 'node:assert/strict';
import {webcrypto} from 'node:crypto';
if(!globalThis.crypto)globalThis.crypto=webcrypto;
const source=fs.readFileSync(new URL('../cloudflare-site/src/worker.js',import.meta.url),'utf8')
  .replace('import { DurableObject } from "cloudflare:workers";', 'class DurableObject {}');
const {SignalingRoom}=await import('data:text/javascript;charset=utf-8,'+encodeURIComponent(source));
const m=new Map();
const storage={async get(k){return m.get(k);},async put(k,v){m.set(k,v);},async delete(k){m.delete(k);}};
const room=new SignalingRoom({storage},{RESEND_API_KEY:'re_test',VERIFY_FROM_EMAIL:'Tawasol <test@example.com>',GOOGLE_CLIENT_ID:'test.apps.googleusercontent.com'});
let verificationCode='';
const realFetch=globalThis.fetch;
globalThis.fetch=async (url,options)=>{
 if(String(url).startsWith('https://api.resend.com/emails')){
  const b=JSON.parse(options.body);
  verificationCode=b.html.match(/(?:<strong>|letter-spacing:9px[^>]*>)(\d{6})/)?.[1]||'';
  return new Response(JSON.stringify({id:'mail-1'}),{status:200});
 }
 if(String(url).startsWith('https://oauth2.googleapis.com/tokeninfo'))return new Response(JSON.stringify({
  aud:'test.apps.googleusercontent.com',iss:'https://accounts.google.com',email_verified:true,
  email:'google@test.example',sub:'test-google-123',exp:Math.floor(Date.now()/1000)+1800
 }),{status:200});
 throw Error('Unexpected network request '+url);
};
async function api(method,path,body){
 const reply=await room.api(new Request('https://unit.example'+path,{method,headers:{'Content-Type':'application/json'},...(body?{body:JSON.stringify(body)}:{})}));
 return {status:reply.status,data:await reply.json()};
}
const get=(path)=>api('GET',path);
const post=(path,body)=>api('POST',path,body);
const check=(label,value)=>{ assert.ok(value,label); console.log('PASS '+label); };
await storage.put('user:alice',{username:'alice',displayName:'Alice',email:'alice@test.example',passwordHash:await crypto.subtle.digest('SHA-256',new TextEncoder().encode('unused')).then(a=>Buffer.from(a).toString('hex')),createdAt:1000000});
await storage.put('user:bob',{username:'bob',displayName:'Bob',email:'bob@test.example',accountPrivate:true,allowFriendRequests:false,createdAt:2000000});
await storage.put('user:carol',{username:'carol',displayName:'Carol',email:'carol@test.example',createdAt:3000000});
await storage.put('usernames',['alice','bob','carol']);
const ta=await room.createSession('alice'),tb=await room.createSession('bob'),tc=await room.createSession('carol');
check('No message to a non-friend', (await post('/api/message-send',{token:ta,to:'bob',text:'hi'})).status===403);
check('No read conversation with non-friend', (await get('/api/conversation?token='+ta+'&with=bob')).status===403);
check('No calls to a non-friend', (await post('/api/call-signal',{token:ta,to:'bob',signal:{type:'call-request'}})).status===403);
check('Friend-request privacy enforced', (await post('/api/friend-request',{token:ta,username:'bob'})).status===403);
await post('/api/profile',{token:tb,allowFriendRequests:true,city:'Riyadh',work:'Engineer',education:'University'});
check('Profile fields saved', (await get('/api/profile-public?token='+ta+'&username=bob')).data.user.city==='Riyadh');
check('Public profile includes creation date', (await get('/api/profile-public?token='+ta+'&username=bob')).data.user.createdAt===2000000);
check('Friend-request succeeds when permitted', (await post('/api/friend-request',{token:ta,username:'bob'})).status===200);
check('Friend accept', (await post('/api/friend-action',{token:tb,username:'alice',action:'accept'})).status===200);
check('Friend message works', (await post('/api/message-send',{token:ta,to:'bob',text:'hello friend'})).status===200);
check('Friend conversation works', (await get('/api/conversation?token='+tb+'&with=alice')).data.messages?.length===1);
check('Friend call signaling works', (await post('/api/call-signal',{token:ta,to:'bob',signal:{type:'call-request'}})).status===200);
await storage.put('postIds',['private-bob']);
await storage.put('post:private-bob',{id:'private-bob',author:'bob',text:'SECRET POST',likes:[],comments:[]});
check('Friend sees private post', (await get('/api/posts?token='+ta)).data.posts.length===1);
check('Stranger cannot see private post', (await get('/api/posts?token='+tc)).data.posts.length===0);
check('Stranger cannot find private post via explore', (await get('/api/explore?token='+tc+'&q=SECRET')).data.posts.length===0);
check('Stranger cannot react private post', (await post('/api/post-like',{token:tc,postId:'private-bob'})).status===403);
check('Stranger cannot share private post', (await post('/api/post-share',{token:tc,postId:'private-bob'})).status===403);
await post('/api/personal-block',{token:tb,username:'alice',action:'block'});
check('Blocked friend cannot send', (await post('/api/message-send',{token:ta,to:'bob',text:'blocked'})).status===403);
check('Blocked friend cannot request', (await post('/api/friend-request',{token:ta,username:'bob'})).status===403);
check('Blocked person cannot read private posts', (await get('/api/posts?token='+ta)).data.posts.length===0);
check('Blocked person cannot read friends list', (await get('/api/friends?token='+ta+'&username=bob')).status===403);
const wsReplies=[];await room.webSocketMessage({deserializeAttachment:()=>({user:'alice',token:ta}),send:(x)=>wsReplies.push(JSON.parse(x))},JSON.stringify({type:'chat',to:'bob',text:'ws no'}));
check('WS chat requires friendship',wsReplies.some(v=>v.type==='error'));
const incorrectLogin=await post('/api/login',{username:'alice',password:'not-the-password'});
check('Invalid password reports login failure, not duplicate registration', incorrectLogin.status===401 && incorrectLogin.data.error==='اسم المستخدم أو كلمة المرور غير صحيحة');
const legacyLogin=await post('/api/login',{username:'alice',password:'unused'});
check('Legacy SHA-256 password login upgrades hash',legacyLogin.status===200 && (await room.user('alice')).passwordHash.startsWith('pbkdf2:'));
const reset=await post('/api/password-reset-request',{email:'alice@test.example'});
check('Reset request sends code',reset.status===200 && /^[0-9]{6}$/.test(verificationCode));
check('Incorrect reset code fails',(await post('/api/password-reset-confirm',{requestId:reset.data.requestId,code:'bad',newPassword:'password123',confirmPassword:'password123'})).status===400);
check('Reset with valid code succeeds',(await post('/api/password-reset-confirm',{requestId:reset.data.requestId,code:verificationCode,newPassword:'password123',confirmPassword:'password123'})).status===200);
check('Reset revokes old sessions',await room.session(ta)===null);
const login=await post('/api/login',{username:'alice',password:'password123'});
check('Login works with PBKDF2',login.status===200 && (await room.user('alice')).passwordHash.startsWith('pbkdf2:'));
check('Change password rejects wrong current one',(await post('/api/change-password',{token:login.data.token,oldPassword:'wrong',newPassword:'newpassword567',confirmPassword:'newpassword567'})).status===401);
check('Change password succeeds',(await post('/api/change-password',{token:login.data.token,oldPassword:'password123',newPassword:'newpassword567',confirmPassword:'newpassword567'})).status===200);
check('Changing password revokes prior session',await room.session(login.data.token)===null);
check('Login with new password works',(await post('/api/login',{username:'alice',password:'newpassword567'})).status===200);
check('Reject invalid username at registration',(await post('/api/register-request',{fullName:'Bad User',username:'alice:bob',email:'invalid@test.example',password:'goodpassword',confirmPassword:'goodpassword',birthDate:'2000-01-01'})).status===400);
const missingLegal=await post('/api/google-login',{credential:'fake.test.token',username:'googleuser',fullName:'Google User',birthDate:'2000-01-01',gender:'male',accountHidden:false,password:'mygoodpassword',confirmPassword:'mygoodpassword'});
check('Google signup rejects missing privacy consent',missingLegal.status===400);
const google=await post('/api/google-login',{credential:'fake.test.token',username:'googleuser',fullName:'Google User',birthDate:'2000-01-01',gender:'male',accountHidden:false,password:'mygoodpassword',confirmPassword:'mygoodpassword',legalAccepted:true,legalVersion:'2026-10-20'});
check('Google validates boolean email_verified and creates account',google.status===200 && google.data.user.username==='googleuser');
check('Google signup password also works',(await post('/api/login',{username:'googleuser',password:'mygoodpassword'})).status===200);
globalThis.fetch=realFetch;
console.log('ALL BACKEND SMOKE TESTS PASSED');
