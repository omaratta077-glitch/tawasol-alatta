import assert from 'node:assert/strict';
import fs from 'node:fs';
import {JSDOM,VirtualConsole} from 'jsdom';
import {webcrypto} from 'node:crypto';
const script=fs.readFileSync(new URL('../../cloudflare-site/public/watch-room.js',import.meta.url),'utf8');
const tick=async()=>{for(let i=0;i<15;i++)await new Promise(r=>setImmediate(r));};
function setup({callBusy=false,mediaFailure=false,defer=false,rejectJoin=false}={}){
 const dom=new JSDOM('<body><div id="watchRoomMount"></div><button data-football-channel="fixture-channel"></button></body>',{url:'https://example.test',runScripts:'outside-only',virtualConsole:new VirtualConsole()});
 const w=dom.window,d=w.document,requests=[],connections=[],timers=[];let played=0,mediaRequests=0,resolveMedia,copied='';
 const audio={kind:'audio',enabled:true,stopped:false,stop(){this.stopped=true;}};
 const video={kind:'video',enabled:true,stopped:false,stop(){this.stopped=true;}};
 const stream={getTracks:()=>[audio,video],getAudioTracks:()=>[audio],getVideoTracks:()=>[video]};
 Object.defineProperty(w,'crypto',{value:webcrypto});
 Object.defineProperty(w.navigator,'mediaDevices',{value:{getUserMedia:async()=>{mediaRequests++;if(mediaFailure)throw Error('permission denied');if(defer)return new Promise(r=>resolveMedia=r);return stream;}}});
 Object.defineProperty(w.navigator,'clipboard',{value:{writeText:async value=>{copied=value;}}});
 w.HTMLMediaElement.prototype.play=()=>Promise.resolve();w.HTMLMediaElement.prototype.pause=()=>{};
 w.setTimeout=callback=>{timers.push(callback);return timers.length;};w.clearTimeout=()=>{};
 const members=[{peerId:'peer-a',username:'owner',name:'صاحب الغرفة',cameraOn:true,micOn:true},{peerId:'peer-b',username:'friend',name:'صاحبي',cameraOn:true,micOn:true}];
 const room={ok:true,code:'A1B2C3D4E5F6',myPeerId:'peer-a',channelId:'fixture-channel',channelName:'Fixture Sports',members,signals:[],cursor:0};
 w.AttaWeb={callBusy:()=>callBusy,navigate:pageId=>d.dispatchEvent(new w.CustomEvent('atta:page',{detail:{pageId}})),watchIce:async()=>({iceServers:[]}),watchRequest:async body=>{
  requests.push(body);if(rejectJoin&&body.action==='create')throw Error('room rejected');
  return body.action==='signal'||body.action==='leave'?{ok:true}:structuredClone(room);
 }};
 w.RTCPeerConnection=class{
  constructor(config){assert.deepEqual(config.iceServers,[]);this.signalingState='stable';connections.push(this);}
  addTrack(){return{getParameters:()=>({encodings:[{}]}),setParameters:async()=>{}};}
  async createOffer(){return{type:'offer',sdp:'fixture SDP'};}
  async setLocalDescription(sdp){this.localDescription=sdp;this.signalingState='have-local-offer';}
  close(){this.closed=true;}
 };
 d.querySelector('[data-football-channel]').onclick=()=>played++;
 w.eval(script);
 return{w,d,audio,video,stream,requests,connections,timers,get played(){return played;},get mediaRequests(){return mediaRequests;},get copied(){return copied;},resolve:()=>resolveMedia(stream)};
}
const s=setup();s.d.querySelector('[data-watch-create]').click();await tick();
assert.equal(s.w.AttaWatchRoom.active,true);assert.equal(s.d.querySelectorAll('[data-watch-peer]').length,2);
assert.equal(s.d.querySelector('[data-watch-count]').textContent,'2/6');
assert.equal(s.connections.length,1);assert.equal(s.requests.filter(x=>x.action==='signal'&&x.signal.type==='offer').length,1,'lower peer negotiates once');
assert.ok(s.requests.every(x=>typeof x.clientId==='string'&&x.clientId.length>=16));
s.d.querySelector('[data-watch-mic]').click();assert.equal(s.audio.enabled,false);
s.d.querySelector('[data-watch-camera]').click();assert.equal(s.video.enabled,false);
s.d.querySelector('[data-watch-copy]').click();await tick();assert.equal(s.copied,'A1B2C3D4E5F6');
s.d.querySelector('[data-watch-play]').click();assert.equal(s.played,1,'channel opens through the existing channel action');
s.w.AttaWeb.navigate('homePage');assert.equal(s.d.querySelector('.watch-ongoing').hidden,false);
s.w.AttaWeb.navigate('sportsPage');assert.equal(s.d.querySelector('.watch-ongoing').hidden,true);
await s.w.AttaWatchRoom.leave();assert.equal(s.w.AttaWatchRoom.active,false);assert.ok(s.audio.stopped&&s.video.stopped&&s.connections.every(p=>p.closed));
assert.equal(s.requests.at(-1).action,'leave');assert.equal(s.d.querySelectorAll('[data-watch-peer]').length,0);
assert.equal(s.d.querySelector('[data-watch-camera]').textContent,'إغلاق الكاميرا');
const denied=setup({mediaFailure:true});denied.d.querySelector('[data-watch-create]').click();await tick();assert.equal(denied.w.AttaWatchRoom.active,false);assert.ok(!denied.requests.some(r=>r.action==='create'));
const privateCall=setup({callBusy:true});privateCall.d.querySelector('[data-watch-create]').click();await tick();assert.equal(privateCall.mediaRequests,0,'group cannot take a private-call camera');
const failed=setup({rejectJoin:true});failed.d.querySelector('[data-watch-create]').click();await tick();assert.ok(failed.audio.stopped&&failed.video.stopped);assert.equal(failed.w.AttaWatchRoom.active,false);
const late=setup({defer:true});late.d.querySelector('[data-watch-create]').click();await tick();await late.w.AttaWatchRoom.leave();late.resolve();await tick();assert.ok(late.audio.stopped&&late.video.stopped);assert.equal(late.w.AttaWatchRoom.active,false);assert.ok(!late.requests.some(r=>r.action==='create'),'late camera grant cannot recreate a departed room');
for(const fixture of [s,denied,privateCall,failed,late])fixture.w.close();
console.log('PASS browser watch-room join/negotiation, private-call exclusion, controls, shared channel action, navigation and immediate/late media cleanup');
