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
  assert.ok(markup.indexOf('sports-browser-player.js?v=14.18') < markup.indexOf('web-modules.js?v=14.18'));
  assert.match(markup,/name="google-site-verification"/);
});
