import assert from 'node:assert/strict';
import fs from 'node:fs';
import {JSDOM} from 'jsdom';
const source=fs.readFileSync(new URL('../../cloudflare-site/public/app.js',import.meta.url),'utf8');
const dom=new JSDOM('<div id="googleSetupHint"></div><div id="googleSignInBtn"></div>',{runScripts:'outside-only',url:'https://unit.test/'});
const w=dom.window,d=w.document;let callback,loggedIn,submissions=0;
w.google={accounts:{id:{initialize:opts=>callback=opts.callback,renderButton(){}}}};
w.eval(source.slice(source.indexOf('function attaCalendarLimit('),source.indexOf('let pendingVerificationId=')));
w.startAuthenticatedApp=async(token,user)=>loggedIn={token,user};
w.api=async(path,opts)=>{
 if(path==='/api/google-config')return{enabled:true,clientId:'fixture'};
 const body=JSON.parse(opts.body);assert.equal(body.credential,'verified-fixture');
 if(!body.username){const error=new Error('اكمل البيانات');error.code='USERNAME_REQUIRED';throw error;}
 assert.equal(body.legalAccepted,true);assert.equal(body.legalVersion,'2026-10-20');
 submissions++;if(submissions===1)throw Error('اسم المستخدم موجود');
 return{token:'fixture-session',user:{username:body.username}};
};
w.eval(source.slice(source.indexOf('function requestGoogleProfile('),source.indexOf('window.AttaWeb=Object.freeze')));
await w.setupGoogleSignIn();const pending=callback({credential:'verified-fixture'});await new Promise(r=>setTimeout(r,0));
const form=d.querySelector('.google-profile-overlay form');assert.ok(form,'new Google users reach signup form');
Object.entries({fullName:'اسم تجريبي',username:'taken',gender:'male'}).forEach(([name,value])=>form.elements[name].value=value);
for(const [part,value] of Object.entries({year:'2000',month:'01',day:'01'})){const field=form.querySelector(`[data-birth-part="${part}"]`);field.value=value;field.dispatchEvent(new w.Event('change'));}
assert.equal(form.elements.birthDate.value,'2000-01-01');
form.dispatchEvent(new w.Event('submit',{cancelable:true}));await new Promise(r=>setTimeout(r,0));assert.equal(submissions,0,'consent cannot be skipped');
form.elements.legalAccepted.checked=true;form.dispatchEvent(new w.Event('submit',{cancelable:true}));await new Promise(r=>setTimeout(r,0));
assert.equal(submissions,1);assert.ok(form.isConnected);assert.equal(form.elements.fullName.value,'اسم تجريبي');assert.match(form.querySelector('[role=alert]').textContent,/موجود/);
form.elements.username.value='available';form.dispatchEvent(new w.Event('submit',{cancelable:true}));await pending;
assert.equal(loggedIn.user.username,'available');assert.equal(d.querySelector('.google-profile-overlay'),null);
console.log('PASS Google identity -> profile -> required consent -> retained validation errors -> authenticated account');w.close();
