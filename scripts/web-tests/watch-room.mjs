import assert from 'node:assert/strict';
import fs from 'node:fs';
import {JSDOM,VirtualConsole} from 'jsdom';
import {webcrypto} from 'node:crypto';
const script=fs.readFileSync(new URL('../../cloudflare-site/public/watch-room.js',import.meta.url),'utf8');
const tick=async()=>{for(let i=0;i<15;i++)await new Promise(r=>setImmediate(r));};
function setup({callBusy=false,mediaFailure=false,defer=false,rejectJoin=false,myPeerId='peer-a',signals=[],failDescriptionOnce=false,pasteText=''}={}){
 const dom=new JSDOM('<body><div id="watchRoomMount"></div><button data-football-channel="fixture-channel"></button></body>',{url:'https://example.test',runScripts:'outside-only',virtualConsole:new VirtualConsole()});
 const w=dom.window,d=w.document,requests=[],connections=[],timers=[];let played=0,mediaRequests=0,resolveMedia,copied='';
 const audio={kind:'audio',enabled:true,stopped:false,stop(){this.stopped=true;}};
 const video={kind:'video',enabled:true,stopped:false,stop(){this.stopped=true;}};
 const stream={getTracks:()=>[audio,video],getAudioTracks:()=>[audio],getVideoTracks:()=>[video]};
 Object.defineProperty(w,'crypto',{value:webcrypto});
 Object.defineProperty(w.navigator,'mediaDevices',{value:{getUserMedia:async()=>{mediaRequests++;if(mediaFailure)throw Error('permission denied');if(defer)return new Promise(r=>resolveMedia=r);return stream;}}});
 Object.defineProperty(w.navigator,'clipboard',{value:{writeText:async value=>{copied=value;},readText:async()=>pasteText}});
 w.HTMLMediaElement.prototype.play=()=>Promise.resolve();w.HTMLMediaElement.prototype.pause=()=>{};
 w.setTimeout=callback=>{timers.push(callback);return timers.length;};w.clearTimeout=()=>{};
 const members=[{peerId:'peer-a',username:'owner',name:'صاحب الغرفة',cameraOn:true,micOn:true},{peerId:'peer-b',username:'friend',name:'صاحبي',cameraOn:true,micOn:true}];
 const room={ok:true,code:'ABC',myPeerId,channelId:'fixture-channel',channelName:'Fixture Sports',members,signals,cursor:signals.length};
 w.AttaWeb={callBusy:()=>callBusy,navigate:pageId=>d.dispatchEvent(new w.CustomEvent('atta:page',{detail:{pageId}})),watchIce:async()=>({iceServers:[]}),watchRequest:async body=>{
  requests.push(body);if(rejectJoin&&body.action==='create')throw Error('room rejected');
  return body.action==='signal'||body.action==='leave'?{ok:true}:{...structuredClone(room),signals:room.signals.filter(e=>e.seq>(body.after||0))};
 }};
 w.RTCPeerConnection=class{
  constructor(config){assert.deepEqual(config.iceServers,[]);this.signalingState='stable';connections.push(this);}
  addTrack(){return{getParameters:()=>({encodings:[{}]}),setParameters:async()=>{}};}
  async createOffer(){return{type:'offer',sdp:'fixture SDP'};}
  async setLocalDescription(sdp){this.localDescription=sdp;this.signalingState=sdp.type==='offer'?'have-local-offer':'stable';}
  async setRemoteDescription(sdp){if(failDescriptionOnce){failDescriptionOnce=false;throw Error('temporary SDP failure');}this.remoteDescription=sdp;this.signalingState=sdp.type==='offer'?'have-remote-offer':'stable';}
  async createAnswer(){return{type:'answer',sdp:'fixture answer'};}
  async addIceCandidate(){}
  close(){this.closed=true;}
 };
 w.MediaStream=class{constructor(tracks=[]){this.tracks=tracks;}getTracks(){return this.tracks;}addTrack(t){this.tracks.push(t);}getAudioTracks(){return this.tracks.filter(t=>t.kind==='audio');}getVideoTracks(){return this.tracks.filter(t=>t.kind==='video');}};
 d.querySelector('[data-football-channel]').onclick=()=>played++;
 w.eval(script);
 return{w,d,audio,video,stream,requests,connections,timers,room,get played(){return played;},get mediaRequests(){return mediaRequests;},get copied(){return copied;},resolve:()=>resolveMedia(stream)};
}
const s=setup();s.d.querySelector('[data-watch-create]').click();await tick();
assert.equal(s.w.AttaWatchRoom.active,true);assert.equal(s.d.querySelectorAll('[data-watch-peer]').length,2);
assert.equal(s.d.querySelector('[data-watch-count]').textContent,'2/6');
assert.equal(s.connections.length,1);assert.equal(s.requests.filter(x=>x.action==='signal'&&x.signal.type==='offer').length,1,'lower peer negotiates once');
assert.ok(s.requests.every(x=>typeof x.clientId==='string'&&x.clientId.length>=16));
s.d.querySelector('[data-watch-mic]').click();assert.equal(s.audio.enabled,false);
s.d.querySelector('[data-watch-camera]').click();assert.equal(s.video.enabled,false);
s.d.querySelector('[data-watch-copy]').click();await tick();assert.equal(s.copied,'ABC');
s.d.querySelector('[data-watch-play]').click();assert.equal(s.played,1,'channel opens through the existing channel action');
const link=s.connections[0],remoteAudio={id:'remote-audio',kind:'audio'},remoteVideo={id:'remote-video',kind:'video'};
link.ontrack({track:remoteAudio,streams:[]});link.ontrack({track:remoteVideo,streams:[]});await tick();
const player=s.d.querySelector('.watch-remote-audio');assert.equal(player.srcObject.getTracks().length,2,'audio and video remain in one remote stream even with separate track events');
assert.ok([...s.d.querySelectorAll('video')].every(v=>v.muted),'video tiles never replay or double the independent friend audio');
s.d.querySelector('[data-watch-volume]').value='0.35';s.d.querySelector('[data-watch-volume]').dispatchEvent(new s.w.Event('input'));assert.equal(player.volume,0.35);
s.d.querySelector('[data-watch-fold]').click();assert.equal(s.d.querySelector('.watch-room').hidden,true);assert.equal(s.d.querySelector('.watch-ongoing').hidden,false);assert.ok(player.isConnected,'minimizing keeps remote audio mounted');
const bubble=s.d.querySelector('.watch-ongoing');bubble.onpointerdown({button:0,pointerId:1,clientX:20,clientY:200});bubble.onpointermove({pointerId:1,clientX:2000,clientY:2000,preventDefault(){}});bubble.onpointerup();
assert.equal(Number.parseFloat(bubble.style.left),s.w.innerWidth-70);assert.ok(Number.parseFloat(bubble.style.top)<=s.w.innerHeight-144);
bubble.querySelector('button').click();assert.equal(s.d.querySelector('.watch-room').hidden,true,'a drag does not accidentally open the room');
bubble.onpointerdown({button:0,pointerId:2,clientX:20,clientY:200});bubble.onpointerup();bubble.querySelector('button').click();assert.equal(s.d.querySelector('.watch-room').hidden,false);
s.w.AttaWeb.navigate('homePage');assert.equal(s.d.querySelector('.watch-ongoing').hidden,false);
s.w.AttaWeb.navigate('sportsPage');assert.equal(s.d.querySelector('.watch-ongoing').hidden,true);
await s.w.AttaWatchRoom.leave();assert.equal(s.w.AttaWatchRoom.active,false);assert.ok(s.audio.stopped&&s.video.stopped&&s.connections.every(p=>p.closed));
assert.equal(s.requests.at(-1).action,'leave');assert.equal(s.d.querySelectorAll('[data-watch-peer]').length,0);
assert.equal(s.d.querySelector('[data-watch-camera]').textContent,'إغلاق الكاميرا');
const denied=setup({mediaFailure:true});denied.d.querySelector('[data-watch-create]').click();await tick();assert.equal(denied.w.AttaWatchRoom.active,false);assert.ok(!denied.requests.some(r=>r.action==='create'));
const privateCall=setup({callBusy:true});privateCall.d.querySelector('[data-watch-create]').click();await tick();assert.equal(privateCall.mediaRequests,0,'group cannot take a private-call camera');
const failed=setup({rejectJoin:true});failed.d.querySelector('[data-watch-create]').click();await tick();assert.ok(failed.audio.stopped&&failed.video.stopped);assert.equal(failed.w.AttaWatchRoom.active,false);
const late=setup({defer:true});late.d.querySelector('[data-watch-create]').click();await tick();await late.w.AttaWatchRoom.leave();late.resolve();await tick();assert.ok(late.audio.stopped&&late.video.stopped);assert.equal(late.w.AttaWatchRoom.active,false);assert.ok(!late.requests.some(r=>r.action==='create'),'late camera grant cannot recreate a departed room');
const retry=setup({myPeerId:'peer-b',failDescriptionOnce:true,signals:[{code:'ABC',from:'peer-a',to:'peer-b',type:'offer',signalId:'fixture-offer-1',seq:1,sdp:{type:'offer',sdp:'fixture SDP'}}]});
retry.d.querySelector('[data-watch-create]').click();await tick();assert.equal(retry.requests.filter(r=>r.action==='signal'&&r.signal.type==='answer').length,0);
retry.timers.shift()();await tick();assert.equal(retry.requests.filter(r=>r.action==='poll').at(-1).after,0,'failed SDP is not acknowledged');
assert.equal(retry.requests.filter(r=>r.action==='signal'&&r.signal.type==='answer').length,1,'same offer is processed successfully after a temporary failure');
retry.timers.shift()();await tick();assert.equal(retry.requests.filter(r=>r.action==='poll').at(-1).after,1,'only a processed signal advances the cursor');await retry.w.AttaWatchRoom.leave();
const paste=setup({pasteText:'انضم لغرفة المشاهدة في العطا. الكود: xyz'});paste.d.querySelector('[data-watch-paste]').click();await tick();assert.equal(paste.d.querySelector('[data-watch-code]').value,'XYZ');
paste.d.querySelector('[data-watch-code]').value='AB1';paste.d.querySelector('[data-watch-join]').click();await tick();assert.equal(paste.mediaRequests,0,'invalid short codes never start the camera');
for(const fixture of [s,denied,privateCall,failed,late,retry,paste])fixture.w.close();
console.log('PASS browser watch-room join/negotiation, private-call exclusion, controls, shared channel action, navigation and immediate/late media cleanup');
