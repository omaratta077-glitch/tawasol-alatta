// Regression checks for the actual browser call-signal handler.
// Runs without network, the browser, WebRTC, or production accounts.
import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';

const source = fs.readFileSync('cloudflare-site/public/app.js', 'utf8');
const start = source.indexOf('async function handleCallSignal(msg){');
const end = source.indexOf('async function pollStoredCallSignals(){', start);
assert.ok(start > 0 && end > start, 'call-signal handler exists');
const handler = source.slice(start, end);
const elements = new Map();
const emitted = [], events = [];
const $ = id => {
  if (!elements.has(id)) {
    const classes = new Set(['hidden']);
    elements.set(id, { textContent: '', classList: {
      add: name => classes.add(name),
      remove: name => classes.delete(name),
      contains: name => classes.has(name)
    }});
  }
  return elements.get(id);
};
const ctx = {
  console, Date, Set,
  CALL_SIGNAL_TYPES: new Set(['call-request', 'call-ringing', 'call-accept', 'call-reject', 'offer', 'answer', 'ice', 'hangup']),
  handledCallSignalIds: new Set(),
  callState: 'idle', activeCallId: '', activeCallPeer: '', incomingFrom: '',
  currentCallType: 'audio', remoteRinging: false, activeRingType: '',
  $, notify: () => {},
  setCallPeerIdentity: () => {},
  setCallState: (state, peer, callId) => {
    ctx.callState = state;
    ctx.activeCallPeer = peer;
    ctx.activeCallId = callId;
  },
  sendCallSignal: payload => {
    if (payload.type === 'call-ringing') {
      assert.equal($('incomingModal').classList.contains('hidden'), false,
        'recipient has actually displayed the incoming dialog');
    }
    emitted.push(payload);
    return Promise.resolve({ok: true});
  },
  playIncomingRing: () => events.push('incoming-sound'),
  playOutgoingRing: () => { events.push('caller-ringback'); ctx.activeRingType = 'outgoing'; },
  callIdMatches: msg => !ctx.activeCallId || ctx.activeCallId === msg.callId,
  clearOutgoingCallTimer: () => {}, stopCallSounds: () => events.push('stop-sounds'),
  showCallOverlay: () => {}, startOffer: async () => {},
  // Unused in these branches; required only if a test reaches the media path.
  teardownPeer: async () => {}, hideCallOverlay: () => {}, resetCallState: () => {}
};
vm.createContext(ctx);
vm.runInContext(handler, ctx, { filename: 'app.js/handleCallSignal' });

// Incoming request: display the incoming UI THEN acknowledge the exact call ID.
await ctx.handleCallSignal({ type:'call-request', from:'caller', callId:'call-1',
  callType:'audio', signalId:'one' });
assert.equal(emitted.length, 1);
assert.equal(emitted[0].type, 'call-ringing');
assert.equal(emitted[0].to, 'caller');
assert.equal(emitted[0].callId, 'call-1');
assert.equal(ctx.callState, 'incoming');
assert.ok(events.includes('incoming-sound'));
console.log('PASS recipient acknowledges ONLY after showing the incoming dialog');

ctx.callState = 'outgoing';
ctx.activeCallPeer = 'friend';
ctx.activeCallId = 'call-2';
ctx.remoteRinging = false;
ctx.activeRingType = '';
events.length = 0;
await ctx.handleCallSignal({type:'call-ringing',from:'stranger',callId:'call-2',signalId:'two'});
assert.equal(ctx.remoteRinging, false);
assert.ok(!events.includes('caller-ringback'));
await ctx.handleCallSignal({type:'call-ringing',from:'friend',callId:'other',signalId:'three'});
assert.equal(ctx.remoteRinging, false);
assert.ok(!events.includes('caller-ringback'));
console.log('PASS stray and old call acknowledgements are ignored');

await ctx.handleCallSignal({type:'call-ringing',from:'friend',callId:'call-2',signalId:'four'});
assert.equal(ctx.remoteRinging, true);
assert.equal($('callPeerState').textContent, 'يرن الآن...');
assert.equal(events.filter(x=>x==='caller-ringback').length, 1);
await ctx.handleCallSignal({type:'call-ringing',from:'friend',callId:'call-2',signalId:'five'});
assert.equal(events.filter(x=>x==='caller-ringback').length, 1,
  'duplicate remote acknowledgement should not restart ringback');
console.log('PASS caller status and ringback start after matching acknowledgement');

await ctx.handleCallSignal({type:'call-accept',from:'friend',callId:'call-2',signalId:'six'});
assert.ok(events.includes('stop-sounds'));
assert.equal(ctx.callState, 'connecting');
console.log('PASS ringback stops when the callee answers');

const requestStart = source.indexOf('async function requestCall(type){');
const requestEnd = source.indexOf('async function startOffer(){', requestStart);
assert.ok(requestStart > 0 && requestEnd > requestStart);
const request = source.slice(requestStart, requestEnd);
assert.ok(!request.includes('playOutgoingRing()'),
  'request itself must not start ringback or claim actual ringing');
assert.ok(!request.includes('result?.online'),
  'socket delivery is not proof that recipient is ringing');

const native = fs.readFileSync('android-native/app/src/main/java/com/alatta/nativeapp/MainActivity.kt','utf8');
assert.ok(native.includes('Modifier.align(Alignment.TopCenter)'));
assert.ok(native.includes('.fillMaxWidth()\n                            .padding(top = 72.dp'));
console.log('PASS minimized in-app call banner is full-width');
console.log('ALL CALL RINGING REGRESSION TESTS PASSED');
