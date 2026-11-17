import {readFileSync} from 'node:fs';
import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';
import path from 'node:path';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const load=name=>JSON.parse(readFileSync(path.join(root,name),'utf8'));
const active=load('cloudflare-site/public/tv-authorized-channels.json');
const proposals=load('alatta-tv/rights/distribution-candidates.json');
const permittedFormats=new Set(['hls','dash']);
const httpsLink=value=>{
 assert.equal(typeof value,'string');
 const url=new URL(value);
 assert.equal(url.protocol,'https:');
 assert.equal(url.username,'');
 assert.equal(url.password,'');
 assert.ok(url.hostname.includes('.')&&!url.hostname.endsWith('.local'));
};
assert.equal(active.schemaVersion,1);
assert.equal(active.app,'AL-ATTA TV');
assert.ok(Array.isArray(active.channels));
assert.equal(proposals.schemaVersion,1);
assert.ok(Array.isArray(proposals.candidates));
const seen=new Set();
for(const item of proposals.candidates){
 assert.match(item.id,/^[a-z0-9-]{2,60}$/);
 assert.ok(!seen.has(item.id));
 seen.add(item.id);
 assert.equal(item.status,'permission_required');
 assert.ok(!('streamUrl' in item),'Unapproved candidates must not contain playable streams');
 assert.ok(typeof item.requestUrl==='string'||typeof item.requestEmail==='string');
 if(item.requestUrl)httpsLink(item.requestUrl);
}
const activeIds=new Set();
for(const channel of active.channels){
 assert.match(channel.id,/^[a-z0-9-]{2,60}$/);
 assert.ok(!activeIds.has(channel.id));
 activeIds.add(channel.id);
 assert.ok(channel.name&&channel.name.length<=120);
 assert.equal(channel.enabled,true);
 assert.ok(permittedFormats.has(channel.format),'Only native HLS/DASH direct playback accepted in redistribution catalog');
 httpsLink(channel.streamUrl);
 assert.ok(/\.(m3u8|mpd)(\?|$)/i.test(new URL(channel.streamUrl).pathname+new URL(channel.streamUrl).search),'Not a direct HLS/DASH URL');
 assert.match(channel.rightsAgreementRef,/^[a-zA-Z0-9._-]{6,120}$/);
 assert.equal(channel.rightsScope,'mobile-internet-redistribution');
 assert.equal(channel.cdnRetransmissionAllowed,true);
 assert.ok(Array.isArray(channel.allowedCountries)&&channel.allowedCountries.length>=1);
 assert.ok(channel.allowedCountries.every(c=>c==='WORLDWIDE'||/^[A-Z]{2}$/.test(c)));
 assert.ok(!channel.allowedCountries.includes('WORLDWIDE')||channel.allowedCountries.length===1);
 assert.ok(/^\d{4}-\d{2}-\d{2}$/.test(channel.rightsValidUntil));
 assert.ok(Date.parse(channel.rightsValidUntil+'T23:59:59Z')>Date.now(),'Expired rights must be removed');
 assert.ok(channel.rightsVerifiedBy&&typeof channel.rightsVerifiedBy==='string');
 assert.ok(channel.sourceAuthority&&/^https:\/\//.test(channel.sourceAuthority));
}
console.log('PASS AL-ATTA TV: '+proposals.candidates.length+' candidates pending rights; '+active.channels.length+' authorized live channels; no unapproved channels published.');
