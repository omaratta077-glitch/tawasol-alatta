import assert from 'node:assert/strict';import fs from 'node:fs';import {JSDOM,VirtualConsole} from 'jsdom';
const read=n=>fs.readFileSync(new URL('../../cloudflare-site/public/'+n,import.meta.url),'utf8');
const dom=new JSDOM(read('index.html'),{url:'https://test.invalid/',runScripts:'outside-only',pretendToBeVisual:true,virtualConsole:new VirtualConsole()});const w=dom.window,d=w.document;
w.queueMicrotask=()=>{};w.setInterval=()=>1;w.setTimeout=()=>1;w.clearTimeout=()=>{};w.matchMedia=()=>({matches:false,addEventListener(){}});w.alert=()=>{};w.confirm=()=>true;w.CSS={escape:s=>s};
w.requestAnimationFrame=fn=>{fn();return 1};w.HTMLElement.prototype.scrollIntoView=function(){this.dataset.scrolled='true'};w.Audio=class{play(){return Promise.resolve()}pause(){}};
const requests=[];w.fetch=async(url,opts={})=>{const u=new URL(url,'https://test.invalid');requests.push({path:u.pathname,body:opts.body});
 if(u.pathname==='/api/post')return Response.json({ok:true,post:{id:'old-post',author:'real-user',authorInfo:{username:'real-user',fullName:'الاسم الحقيقي'},text:'منشور قديم',comments:[],likes:[]}});
 if(u.pathname==='/api/profile-public')return Response.json({ok:true,user:{username:u.searchParams.get('username'),fullName:'الاسم الحقيقي'},friendState:'friends'});
 return Response.json({ok:true,posts:[],users:[],requests:[],friends:[],notifications:[],stories:[]});};
w.eval(read('icons.js'));const app=read('app.js'),end=app.indexOf('})();\n\n\n/* ===== V13.28');w.eval(app.slice(0,end)+`window.readerTest={renderPostText,bindPostMedia,needsPostReader};window.notificationTest={openNotificationTarget,setIncomingCallText,setup(){token='fixture';me={username:'me'}},incoming(peer){incomingFrom=peer}};`+app.slice(end));const api=w.notificationTest;api.setup();

const full='أذكار الصباح\n\n'+Array.from({length:35},(_,i)=>`الذكر ${i}\n`+'نص الذكر كاملًا '.repeat(25)).join('\n\n')+'\n\nآخر ذكر محفوظ <script>bad()</script>';
const post={id:'dhikr-full',text:full};
d.querySelector('#postsFeed').innerHTML=w.readerTest.renderPostText(post);
w.readerTest.bindPostMedia([post],d.querySelector('#postsFeed'));
const preview=d.querySelector('[data-read-post]');assert.ok(preview);assert.ok(preview.textContent.length<400);assert.ok(!preview.textContent.includes('آخر ذكر محفوظ'));
preview.click();const reader=d.querySelector('.post-reader-text');assert.ok(reader);assert.equal([...reader.querySelectorAll('p')].map(p=>p.textContent).join('\n\n'),full);assert.equal(reader.querySelector('script'),null);assert.equal(d.querySelector('[role=dialog]').getAttribute('aria-label'),'المنشور كاملًا');
assert.equal(w.readerTest.needsPostReader('منشور قصير'),false);
dom.window.close();console.log('PASS compact long-post preview opens complete ordered text safely, including final dhikr');
