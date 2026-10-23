import assert from 'node:assert/strict';
import fs from 'node:fs';
import {JSDOM,VirtualConsole} from 'jsdom';
const pub=new URL('../../cloudflare-site/public/',import.meta.url);
const read=name=>fs.readFileSync(new URL(name,pub),'utf8');
for(const mobile of [true,false]){
 const errors=[];
 const dom=new JSDOM(read('index.html'),{runScripts:'outside-only',url:'https://unit.test/',virtualConsole:new VirtualConsole().on('jsdomError',e=>errors.push(e.message))});
 const w=dom.window,d=w.document;let pageListener,shared;
 w.matchMedia=()=>({matches:mobile,addEventListener(){}});
 w.HTMLElement.prototype.scrollIntoView=function(){};
 w.fetch=async()=>Response.json({ok:true,items:[{id:'dQw4w9WgXcQ',title:'عنوان تجريبي',channel:'قناة',thumbnail:'/logo-premium.png'}]});
 w.AttaWeb={navigate(pageId){d.querySelector('#appView').dataset.page=pageId;d.dispatchEvent(new w.CustomEvent('atta:page',{detail:{pageId}}));},preparePost(text){shared=text}};
 w.AttaYouTubeEmbed={create(){const iframe=d.createElement('iframe');iframe.title='fixture';return iframe}};
 w.eval(read('youtube-section.js'));
 w.AttaWeb.navigate('youtubePage');
 if(!mobile)d.querySelector('#attaYouTubeBtn').click();
 await new Promise(r=>setTimeout(r,0));
 const panel=d.querySelector('.atta-youtube-panel');
 assert.equal(panel.parentNode.id==='youtubeInlineMount',mobile);
 assert.equal(d.querySelectorAll('.atta-yt-card').length,1);
 d.querySelector('.atta-yt-card').click();assert.equal(d.querySelectorAll('.atta-youtube-frame iframe').length,1);
 d.querySelector('.atta-yt-share').click();assert.match(shared,/\[\[alatta-youtube:dQw4w9WgXcQ\]\]/);
 if(mobile){
  w.AttaWeb.navigate('homePage');assert.equal(d.querySelectorAll('.atta-youtube-frame iframe').length,0);
  // Run the unchanged native integration against the actual website controller.
  const bridge=fs.readFileSync(new URL('../../android-native/app/src/main/assets/atta-site-youtube-bridge.js',import.meta.url),'utf8');
  w.eval(bridge.replace('__ALATTA_VIDEO_ID__','dQw4w9WgXcQ'));
  assert.equal(panel.parentNode.className,'atta-youtube-overlay');
  assert.equal(d.querySelector('.atta-youtube-overlay').hidden,false);
  assert.equal(d.querySelectorAll('.atta-youtube-frame iframe').length,1);
 }else{d.querySelector('.atta-youtube-close').click();assert.equal(d.querySelector('.atta-youtube-overlay').hidden,true);}
 assert.deepEqual(errors,[]);dom.window.close();
}
console.log('PASS one YouTube controller: mobile inline, desktop dialog, sharing, leave-page stop, unchanged native bridge');
