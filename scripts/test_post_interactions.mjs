import assert from 'node:assert/strict';
import fs from 'node:fs';import {webcrypto} from 'node:crypto';
globalThis.crypto??=webcrypto;
const source=fs.readFileSync(new URL('../cloudflare-site/src/worker.js',import.meta.url),'utf8').replace('import { DurableObject } from "cloudflare:workers";','class DurableObject {}');
const {SignalingRoom}=await import('data:text/javascript;charset=utf-8,'+encodeURIComponent(source));
const map=new Map(),storage={async get(k){return map.get(k)},async put(k,v){map.set(k,v)},async delete(k){map.delete(k)}};
const room=new SignalingRoom({storage,waitUntil:p=>p.catch(()=>{}),getWebSockets:()=>[]},{});
for(const u of [
 {username:'owner',fullName:'الناشر'},
 {username:'alice',fullName:'أسماء المستخدم الحقيقية',avatar:'/media/alice.jpg',email:'private@example.test'},
 {username:'bob',fullName:'شخص آخر'},
 {username:'hidden',accountHidden:true,gender:'female'},
 {username:'blocked'}, {username:'disabled',disabled:true},
 {username:'private',accountPrivate:true}
])await storage.put('user:'+u.username,u);
await storage.put('blocked:owner',['blocked']);await storage.put('blocked:alice',['blocked']);
const tokens={};for(const name of ['owner','alice','bob'])tokens[name]=await room.createSession(name);
const post={id:'p1',author:'owner',likes:['alice','alice','bob','hidden','blocked','disabled'],reactions:{love:['alice'],haha:['bob'],wow:['hidden']},comments:[
 {id:'c1',author:'alice',text:'تعليق <script>لا ينفذ</script>',replies:[{id:'r1',author:'hidden',text:'private'}]},
 {id:'c2',author:'hidden',text:'private'}, {id:'c3',author:'bob',text:'تعليق آخر'}
]};
await storage.put('post:p1',post);await storage.put('postIds',['p1']);
await storage.put('post:archived',{id:'archived',author:'owner',archived:true});
await storage.put('post:private',{id:'private',author:'private',likes:[],comments:[]});
async function request(path,body){const res=await room.api(new Request('https://test.invalid'+path,{method:body?'POST':'GET',...(body?{headers:{'content-type':'application/json'},body:JSON.stringify(body)}:{})}));return{status:res.status,data:await res.json()};}
const endpoint=(user='owner',args='')=>'/api/post-interactions?token='+tokens[user]+'&postId=p1'+args;
const before=JSON.stringify([...map.entries()]);
let result=await request(endpoint('owner','&limit=1'));
assert.equal(result.status,200);assert.equal(result.data.total,3);assert.equal(result.data.reactionCount,3);assert.equal(result.data.nextOffset,1);
assert.deepEqual(result.data.people,[{username:'alice',fullName:'أسماء المستخدم الحقيقية',displayName:'alice',avatar:'/media/alice.jpg',reaction:'love'}]);
assert.equal(JSON.stringify([...map.entries()]),before,'Opening the people list does not add/remove likes or write user data');
result=await request(endpoint('owner','&offset=1&limit=1'));assert.equal(result.data.people[0].username,'bob');assert.equal(result.data.nextOffset,2);
result=await request(endpoint('alice','&kind=comments'));assert.equal(result.data.comments.length,3);assert.equal(result.data.comments[2].authorInfo.fullName,'أسماء المستخدم الحقيقية');assert.equal(result.data.comments[2].replies.length,1);
assert.ok(!JSON.stringify(result.data).includes('private@example.test')&&!JSON.stringify(result.data).includes('hidden'));
assert.equal((await request('/api/post-interactions?postId=p1')).status,401);
assert.equal((await request(endpoint('owner','&kind=invalid'))).status,400);
assert.equal((await request('/api/post-interactions?token='+tokens.alice+'&postId=private')).status,404);
assert.equal((await request('/api/post-interactions?token='+tokens.owner+'&postId=archived')).status,404);
result=await request('/api/post-react',{token:tokens.alice,postId:'p1',reaction:'wow'});assert.equal(result.data.reactionCount,3);assert.equal(result.data.myReaction,'wow');
assert.ok(!post.likes.includes('alice'));assert.ok(!post.reactions.love.includes('alice'));assert.deepEqual(post.reactions.wow.filter(x=>x==='alice'),['alice']);
await request('/api/post-like',{token:tokens.alice,postId:'p1'});assert.equal(post.likes.filter(x=>x==='alice').length,1);assert.ok(!post.reactions.wow.includes('alice'));
assert.equal((await request('/api/posts?token='+tokens.alice)).data.posts[0].myReaction,'like','A legacy like is reflected in the canonical feed selection');
await request('/api/post-react',{token:tokens.alice,postId:'p1',reaction:'like'});assert.ok(!post.likes.includes('alice'));
await request('/api/post-react',{token:tokens.alice,postId:'p1',reaction:'like',remove:true});assert.equal((await request(endpoint())).data.reactionCount,2);
await request('/api/post-comment',{token:tokens.alice,postId:'p1',text:'تعليق جديد'});
result=await request(endpoint('alice','&kind=comments'));assert.equal(result.data.comments[0].text,'تعليق جديد');assert.equal(result.data.comments[0].authorInfo.fullName,'أسماء المستخدم الحقيقية');assert.equal(result.data.commentCount,4);
for(const name of ['owner','hidden'])await storage.put('friends:'+name,[name==='owner'?'hidden':'owner']);
result=await request(endpoint());assert.ok(result.data.people.some(x=>x.anonymous && x.fullName==='حساب مخفي'),'Even friends see anonymized public interactions');
result=await request(endpoint('alice'));assert.ok(!result.data.people.some(x=>x.username==='hidden'),'Friendship with the author does not disclose the identity to strangers');
const website=fs.readFileSync(new URL('../cloudflare-site/public/app.js',import.meta.url),'utf8');
const android=fs.readFileSync(new URL('../android-native/app/src/main/java/com/alatta/nativeapp/MainActivity.kt',import.meta.url),'utf8');
const control=fs.readFileSync(new URL('../android-native/app/src/main/java/com/alatta/nativeapp/PremiumChrome.kt',import.meta.url),'utf8');
assert.ok(website.includes("scope.querySelectorAll('[data-post-comments]')") &&
  website.includes('data-post-comments="') &&
  website.includes('openPostPanel(button.dataset.postComments,"comments")'),
  'Website post and profile comment-count buttons open the existing named-comments sheet');
assert.ok(website.includes('data-post-people="') &&
  website.includes('openPostPanel(button.dataset.postPeople,"reactions")'),
  'Website reaction counts open the existing people panel');
assert.ok(!website.includes('data-comment-focus='), 'old focus-only comments counter removed');
assert.ok(android.includes('private fun AttaPostReactorsDialog(') &&
  android.includes('onShowPeople = { showReactors = true }') &&
  android.includes('get("/api/post-interactions?postId=') &&
  android.includes('kind=reactions&offset=$offset&limit=30'),
  'Android opens the read-only paginated named-reactors dialog through the same API');
assert.ok(android.includes('p.optInt("reactionCount"') &&
  android.includes('AttaRightAlignedComments('),
  'Android counts unique reactors and retains the existing named-comment dialog');
assert.ok(control.includes('onShowPeople: (() -> Unit)? = null'),
  'Existing reaction count affordance reused instead of a duplicate like control');
console.log('PASS post interaction API: names/avatars, read-only deduplicated people pagination, comments, hidden/blocked/disabled/private authorization, reaction switching and new comments plus web/native UI links');
