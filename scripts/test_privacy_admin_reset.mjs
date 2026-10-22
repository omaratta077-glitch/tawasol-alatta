import assert from 'node:assert/strict';
import {enrollAdmin,ADMIN_TEST_PASSWORD} from './admin_security_fixture.mjs';
import fs from 'node:fs';
import {webcrypto} from 'node:crypto';
if(!globalThis.crypto)globalThis.crypto=webcrypto;
const text=fs.readFileSync(new URL('../cloudflare-site/src/worker.js',import.meta.url),'utf8');
const source=text.replace('import { DurableObject } from "cloudflare:workers";', 'class DurableObject {}');
const {SignalingRoom}=await import('data:text/javascript;charset=utf-8,'+encodeURIComponent(source));
const map=new Map();
let deletes=0;
const storage={
 async get(k){return map.get(k)},
 async put(k,v){map.set(k,v)},
 async delete(k){deletes++;map.delete(k)},
 async list({startAfter='',limit=1000}={}){
   return new Map([...map.entries()].sort((a,b)=>a[0].localeCompare(b[0],'en')).filter(([key])=>key>startAfter).slice(0,limit));
 }
};
const media={async list({cursor,limit=1000}={}){
 const all=[{key:'image/test.png',size:5,etag:'xyz'}];
 const start=cursor?Number(cursor):0;
 const objects=all.slice(start,start+limit);
 return {objects,truncated:start+limit<all.length,cursor:String(start+limit)};
}};
const room=new SignalingRoom({storage},{SUPER_ADMIN_USERNAME:'omar1',MEDIA_BUCKET:media});
async function api(method,path,body){
 const req=new Request('https://unit.example'+path,{method,headers:{'Content-Type':'application/json'},...(body?{body:JSON.stringify({...body,adminPassword:ADMIN_TEST_PASSWORD})}:{})});
 const res=await room.api(req);
 return {status:res.status,data:await res.json()};
}
const get=path=>api('GET',path);
const post=(path,body)=>api('POST',path,body);
const check=(label,condition)=>{assert.ok(condition,label);console.log('PASS '+label)};
for(const u of [
 {username:'omar1',gender:'male'},
 {username:'alice',gender:'male'},
 {username:'dina',gender:'female',accountHidden:true,displayName:'Dina'},
 {username:'bob',gender:'male'},
]) await storage.put('user:'+u.username,{displayName:u.username,...u});
await storage.put('usernames',['omar1','alice','dina','bob']);
const sessions=Object.fromEntries(await Promise.all(['omar1','alice','dina','bob'].map(async u=>[u,await room.createSession(u)])));
const q='/api/users?token='+sessions.alice;
check('Hidden account not searchable', !(await get(q+'&q=dina')).data.users.some(u=>u.username==='dina'));
check('Hidden account not in general directory', !(await get(q)).data.users.some(u=>u.username==='dina'));
check('Hidden account owner can view own profile', (await get('/api/profile-public?token='+sessions.dina+'&username=dina')).status===200);
check('Stranger cannot fetch hidden account directly', (await get('/api/profile-public?token='+sessions.alice+'&username=dina')).status===404);
check('Stranger cannot invite hidden user', (await post('/api/friend-request',{token:sessions.alice,username:'dina'})).status===404);
check('Hidden user can send friend request', (await post('/api/friend-request',{token:sessions.dina,username:'bob'})).status===200);
check('Recipient may view hidden sender of friend request', (await get('/api/profile-public?token='+sessions.bob+'&username=dina')).status===200);
check('Recipient can accept hidden sender', (await post('/api/friend-action',{token:sessions.bob,username:'dina',action:'accept'})).status===200);
check('Accepted friend can communicate', (await post('/api/message-send',{token:sessions.dina,to:'bob',text:'hello'})).status===200);
check('Accepted hidden friend remains absent from friend public list', !(await get('/api/friends?token='+sessions.alice+'&username=bob')).data.friends.some(u=>u.username==='dina'));
check('Owner sees their own hidden friend', (await get('/api/friends?token='+sessions.bob)).data.friends.some(u=>u.username==='dina'));
check('Hidden account does not appear in directory after friendship', !(await get('/api/users?token='+sessions.bob)).data.users.some(u=>u.username==='dina'));
check('Unauthorised user cannot preview reset', (await post('/api/admin/reset/preview',{token:sessions.alice})).status===403);
check('Unauthorised user cannot export DO', (await post('/api/admin/reset/export',{token:sessions.alice})).status===403);
check('Unauthorised user cannot export R2 inventory', (await post('/api/admin/reset/media-list',{token:sessions.alice})).status===403);
const ownerFixture=await storage.get("user:omar1");ownerFixture.email="owner@example.test";await storage.put("user:omar1",ownerFixture);room.env.SUPER_ADMIN_EMAIL="owner@example.test";
await enrollAdmin(room,sessions.omar1);
const deletesBeforeReview=deletes;
const preview=await post('/api/admin/reset/preview',{token:sessions.omar1});
check('Owner can preview without deletion', preview.status===200 && preview.data.dryRun===true && preview.data.keepAccount==="omar1" && preview.data.deleteOwnerAccount===false && preview.data.media.count===1);
const exported=[];
let cursor='';
while(true){
 const batch=await post('/api/admin/reset/export',{token:sessions.omar1,startAfter:cursor,limit:2});
 check('Owner export page is successful',batch.status===200);
 exported.push(...batch.data.entries);
 if(!batch.data.next)break;
 assert.notEqual(batch.data.next,cursor);cursor=batch.data.next;
}
check('Complete read-only backup page iteration', exported.length===preview.data.durableObjectKeys && exported.some(e=>e.key==='user:omar1'));
check('Owner can inventory media', (await post('/api/admin/reset/media-list',{token:sessions.omar1})).data.objects[0].key==='image/test.png');
check('No data was deleted during preview or export', deletes===deletesBeforeReview && !!(await storage.get('user:omar1')));
// Google account creation is equally protected (without calling Google test network).
check('Gender is required for standard registration', Boolean(await room.validateRegistration({fullName:'X Test',username:'newname',age:20,email:'new@test.com',gender:'',password:'123456',confirmPassword:'123456'})));
check('Female hiding choice is mandatory', (await room.validateRegistration({fullName:'X Test',username:'newname',age:20,email:'new@test.com',gender:'female',password:'123456',confirmPassword:'123456'})).includes('إخفاء'));
check('Male registration cannot request hidden account', (await room.validateRegistration({fullName:'X Test',username:'newname',age:20,email:'new@test.com',gender:'male',accountHidden:true,password:'123456',confirmPassword:'123456'})).includes('متاح'));
// Hidden identities must also be removed from nested public content and legacy data.
const publicPost={id:'public',author:'bob',text:'visible sample',likes:['dina','bob'],reactions:{love:['dina']},comments:[
 {id:'hidden-comment',author:'dina',text:'private'},
 {id:'public-comment',author:'bob',text:'visible',replies:[{id:'hidden-reply',author:'dina',text:'private reply'}]}
]};
await storage.put('post:public',publicPost);
await storage.put('post:hidden-source',{id:'hidden-source',author:'dina',text:'private source',likes:[],comments:[]});
await storage.put('post:shared',{id:'shared',author:'bob',text:'visible shared sample',sharedPostId:'hidden-source',sharedFrom:'dina',likes:[],comments:[]});
await storage.put('postIds',['public','hidden-source','shared']);
await storage.put('saved:alice',['public','shared']);
for(const path of ['/api/posts','/api/saved-posts','/api/explore']){
 const result=await get(path+'?token='+sessions.alice+'&q=visible');
 check('Hidden identity absent from '+path, result.status===200&&!JSON.stringify(result.data).includes('dina'));
}
const friendFeed=await get('/api/posts?token='+sessions.bob);
check('Confirmed friend can still see hidden friend interactions',JSON.stringify(friendFeed.data).includes('hidden-comment'));
await storage.put('notifications:alice',[{id:'legacy-hidden',from:'dina',type:'comment'},{id:'visible-note',from:'bob',type:'comment'}]);
check('Legacy notifications do not reveal hidden stranger',(await get('/api/notifications?token='+sessions.alice)).data.notifications.length===1);
await storage.put('groupIds',['legacy']);await storage.put('group:legacy',{id:'legacy',createdBy:'dina',members:['alice','dina','bob']});
check('Legacy group metadata hides unknown hidden creator',!JSON.stringify((await get('/api/groups?token='+sessions.alice)).data).includes('dina'));
console.log('ALL PRIVACY AND OWNER PREVIEW TESTS PASSED');
