import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';

const source=await readFile(new URL('../../cloudflare-site/public/sports-browser-player.js',import.meta.url),'utf8');
const modules=await readFile(new URL('../../cloudflare-site/public/web-modules.js',import.meta.url),'utf8');
const markup=await readFile(new URL('../../cloudflare-site/public/index.html',import.meta.url),'utf8');

function harness() {
  const loaded=[];
  let lastHls,lastDash;
  const window={};
  function element(type) {
    return {
      type, style:{}, children:[], events:{}, textContent:'', stopped:false,
      append(...children){this.children.push(...children);},
      replaceChildren(...children){this.children=[...children];},
      setAttribute(){}, removeAttribute(){},
      addEventListener(name,callback){this.events[name]=callback;},
      scrollIntoView(){}, pause(){this.stopped=true;}, load(){},
      canPlayType(){return '';},play(){return Promise.resolve();}
    };
  }
  class Hls {
    static Events={ERROR:'error',MANIFEST_PARSED:'manifest'};
    static isSupported(){return true;}
    constructor(){this.handlers={};lastHls=this;}
    on(event,fn){this.handlers[event]=fn;}
    attachMedia(video){this.video=video;}
    loadSource(url){this.url=url;this.handlers.manifest?.();}
    destroy(){this.destroyed=true;}
  }
  function MediaPlayer(){return {create(){lastDash={
    handlers:{},initialize(video,url){this.video=video;this.url=url;},
    on(name,fn){this.handlers[name]=fn;},reset(){this.destroyed=true;}
  };return lastDash;}};}
  MediaPlayer.events={ERROR:'error'};
  const document={
    createElement:element,
    head:{append(script){
      loaded.push(script.src);
      if(script.src.includes('hls.js'))window.Hls=Hls;
      if(script.src.includes('dashjs'))window.dashjs={MediaPlayer};
      script.onload();
    }}
  };
  vm.runInNewContext(source,{window,document,URL,Promise});
  return {player:window.AttaSportsPlayer,element,loaded,window,getHls:()=>lastHls,getDash:()=>lastDash};
}

test('website sports player loads HLS in-page and tears down the instance when stopped',async()=>{
  const h=harness(),host=h.element('div');
  assert.equal(await h.player.play(host,'https://licensed.example/live/stream.m3u8','hls'),true);
  assert.equal(h.player.active(),true);
  assert.equal(h.getHls().url,'https://licensed.example/live/stream.m3u8');
  assert.equal(h.loaded.length,1);
  assert.ok(host.children.some(x=>x.type==='video'));
  h.player.stop();
  assert.equal(h.player.active(),false);
  assert.equal(h.getHls().destroyed,true);
});

test('website sports player initializes DASH and cleans previous HLS player on switch',async()=>{
  const h=harness(),host=h.element('div');
  await h.player.play(host,'https://licensed.example/play.m3u8','hls');
  const previous=h.getHls();
  assert.equal(await h.player.play(host,'https://licensed.example/play.mpd','dash'),true);
  assert.equal(previous.destroyed,true);
  assert.equal(h.getDash().url,'https://licensed.example/play.mpd');
  h.player.stop();
  assert.equal(h.getDash().destroyed,true);
});

test('website sports player rejects HTTP and missing URLs rather than making unsafe requests',async()=>{
  const h=harness(),host=h.element('div');
  assert.equal(await h.player.play(host,'http://example.test/live.m3u8','hls'),false);
  assert.equal(await h.player.play(host,'https://licensed.example/','ytv'),false);
  assert.equal(h.player.active(),false);
  assert.equal(h.loaded.length,0);
});

test('web modules do not install Android helper; preserve live media on catalog refresh',()=>{
  assert.doesNotMatch(modules,/com\.ytv\.pronew|تثبيت المشغّل/);
  assert.match(modules,/AttaSportsPlayer\.play\(host,url,channel\.format\)/);
  assert.match(modules,/playingHost\?\.remove\(\)/);
  assert.match(modules,/replaceWith\(playingHost\)/);
  assert.ok(markup.indexOf('sports-browser-player.js?v=14.20') < markup.indexOf('web-modules.js?v=14.19'));
  assert.match(modules,/bridge\.sportsPlayback\(channel\.id\)/);
  assert.match(markup,/name="google-site-verification"/);
});

test('web player accepts signed HTTPS HLS sources but not HTTP, missing formats or forbidden headers',async()=>{
  const h=harness(),host=h.element('div');
  const sources=[
    {url:'http://unsafe.example/stream.m3u8',headers:{},mimeType:'application/x-mpegURL'},
    {url:'https://licensed.example/live.m3u8',headers:{Referer:'https://third-party.example'},mimeType:'application/x-mpegURL'},
    {url:'https://licensed.example/stream.m3u8?t=short-lived',headers:{'User-Agent':'Android UA'},mimeType:'application/x-mpegURL'}
  ];
  assert.equal(await h.player.playSources(host,sources),true);
  assert.equal(h.getHls().url,'https://licensed.example/stream.m3u8?t=short-lived');
  assert.equal(h.loaded.length,1);
  assert.equal(await h.player.playSources(host,[{url:'http://example.test/foo.m3u8',mimeType:'application/x-mpegURL'}]),false);
  assert.equal(h.player.active(),false);
});

test('switches to another HTTPS browser source if the first source reports a fatal playback error',async()=>{
  const h=harness(),host=h.element('div');
  const candidates=[
    {url:'https://licensed.example/first.m3u8',mimeType:'application/x-mpegURL'},
    {url:'https://licensed.example/second.m3u8',mimeType:'application/x-mpegURL'}
  ];
  await h.player.playSources(host,candidates);
  const first=h.getHls();assert.equal(first.url,candidates[0].url);
  first.handlers.error(null,{fatal:true});
  // the fallback is async because the player bootstraps a second instance.
  await new Promise(r=>setImmediate(r));
  assert.equal(h.getHls().url,candidates[1].url);
  assert.equal(first.destroyed,true);
});
test('website app exposes authenticated existing football playback API but never publishes a session token in markup',async()=>{
  const app=await readFile(new URL('../../cloudflare-site/public/app.js',import.meta.url),'utf8');
  assert.match(app,/sportsPlayback:\(channelId\)=>/);
  assert.match(app,/api\("\/api\/football\/playback",\{method:"POST"/);
  assert.doesNotMatch(markup,/sessionToken|PROBE_KEY/);
});

test('failed HLS source reports safe HTTP code and counts without exposing temporary streaming URL',async()=>{
  const h=harness(),host=h.element('div');
  const signed='https://licensed.example/live/master.m3u8?t=secret-sample&key=demo';
  await h.player.playSources(host,[{url:signed,mimeType:'application/x-mpegURL',headers:{}}]);
  h.getHls().handlers.error(null,{fatal:true,details:'manifestLoadError',response:{code:403}});
  await new Promise(r=>setImmediate(r));
  const message=host.children.map(x=>x.textContent).join(' ');
  assert.match(message,/HTTP 403/);
  assert.match(message,/وصل 1 مصدر/);
  assert.doesNotMatch(message,/secret-sample|licensed\.example|key=demo/);
});

test('failed HLS manifest without readable HTTP status reports CORS as possibility, not proven cause',async()=>{
  const h=harness(),host=h.element('div');
  await h.player.playSources(host,[{url:'https://licensed.example/live.m3u8',mimeType:'application/x-mpegURL'}]);
  h.getHls().handlers.error(null,{fatal:true,details:'manifestLoadError'});
  await new Promise(r=>setImmediate(r));
  const message=host.children.map(x=>x.textContent).join(' ');
  assert.match(message,/قد يكون بسبب الشبكة أو CORS/);
  assert.doesNotMatch(message,/مؤكد|100%/);
});
