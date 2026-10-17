import fs from 'node:fs';
import assert from 'node:assert/strict';
import vm from 'node:vm';
const main=fs.readFileSync('android-native/app/src/main/java/com/alatta/nativeapp/MainActivity.kt','utf8');
const bridge=fs.readFileSync('android-native/app/src/main/assets/atta-site-youtube-bridge.js','utf8');
const shared=fs.readFileSync('cloudflare-site/public/youtube-player-shared.js','utf8');
const site=fs.readFileSync('cloudflare-site/public/youtube-section.js','utf8');
assert.ok(main.includes('loadUrl("$BASE/")'), 'loads the actual site HTTPS document');
assert.ok(main.includes('atta-site-youtube-bridge.js'), 'uses the site UI bridge');
assert.ok(!main.includes('document.body.replaceChildren(host)'),'never destroys the website DOM');
assert.ok(!main.includes('new YT.Player(') && !main.includes('loadDataWithBaseURL('),'no second Android player');
assert.ok(bridge.includes("btn.click()") && bridge.includes("form.dispatchEvent(new Event('submit'"),"uses the site form and click handler");
assert.ok(!bridge.includes("createElement('iframe')") && !bridge.includes('new YT.Player('),'bridge creates no player');
assert.ok(site.includes('window.AttaYouTubeEmbed.create'),'site is sole iframe client');
assert.ok(shared.includes('global.AttaYouTubeEmbed=Object.freeze({create})'));
new vm.Script(bridge.replace('__ALATTA_VIDEO_ID__','dQw4w9WgXcQ'));
assert.ok(main.includes('modifier = modifier.height(39.dp)'),'39dp search preserved');
// Exercise the DOM bridge with a fake already-initialized site player UI.
// The iframe creation is attributed exclusively to the site's form handler.
let iframeCount = 0, siteClicks = 0, searchSubmits = 0;
const logs = [];
const iframeListeners = {};
const iframe = {addEventListener(type, cb){iframeListeners[type]=cb;},contentWindow:{postMessage(){}}};
const input = {value:''};
const form = {
  querySelector(sel){assert.equal(sel,'input');return input;},
  dispatchEvent(ev){assert.equal(ev.type,'submit');searchSubmits++;iframeCount++;return true;}
};
const panel = {};
const overlay = {hidden:true, querySelector(sel){
  if(sel==='.atta-youtube-form')return form;
  if(sel==='.atta-youtube-panel')return panel;
  if(sel==='.atta-youtube-frame iframe')return searchSubmits?iframe:null;
  return null;
}};
const btn = {click(){siteClicks++;overlay.hidden=false;}};
const head = {appendChild(node){assert.equal(node.id,'atta-android-youtube-style');}};
const document = {
  head, activeElement:{blur(){}}, getElementById(key){return key==='attaYouTubeBtn'?btn:null;},
  querySelector(sel){return sel==='.atta-youtube-overlay'?overlay:null;},
  createElement(tag){assert.equal(tag,'style');return {id:'',textContent:''};}
};
const events = [];
const window = {addEventListener(type,fn){events.push({type,fn});}};
const context = vm.createContext({window, document, console:{log(s){logs.push(s)}},
  Event:class Event{constructor(type){this.type=type;}}, URL, setTimeout(){throw Error('Unexpected retry')},
  JSON});
vm.runInContext(bridge.replace('__ALATTA_VIDEO_ID__','dQw4w9WgXcQ'),context);
assert.equal(siteClicks,1);
assert.equal(searchSubmits,1);
assert.equal(iframeCount,1);
assert.equal(input.value,'https://www.youtube.com/watch?v=dQw4w9WgXcQ');
assert.equal(overlay.hidden,false);
iframeListeners.load();
assert.ok(logs.includes('ALATTA_YT:SITE_PLAYER_USED'));
assert.ok(logs.includes('ALATTA_YT:FRAME_LOADED'));
const ytevent=events.find(e=>e.type==='message');
ytevent.fn({origin:'https://www.youtube-nocookie.com', data:JSON.stringify({event:'onError',info:153})});
assert.ok(logs.includes('ALATTA_YT:PLAYER_ERROR:153'));
console.log('PASS: existing live site YouTube UI drives sole iframe, site DOM untouched; 39dp search preserved');
