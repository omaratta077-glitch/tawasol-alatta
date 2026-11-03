import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFile} from 'node:fs/promises';
const source=await readFile(new URL('../../cloudflare-site/public/ytv-web-launch.js',import.meta.url),'utf8');
const modules=await readFile(new URL('../../cloudflare-site/public/web-modules.js',import.meta.url),'utf8');
const html=await readFile(new URL('../../cloudflare-site/public/index.html',import.meta.url),'utf8');
const help=await readFile(new URL('../../cloudflare-site/public/ytv-open.html',import.meta.url),'utf8');
function helper(userAgent='Mozilla/5.0 (Linux; Android 15; Mobile) Chrome/155') {
  const window={navigator:{userAgent},location:{origin:'https://tawasol-alatta.example'}};
  vm.runInNewContext(source,{window,URL,BigInt});
  return window.AttaYtvWebLaunch;
}
test('Android link mirrors existing YTV package, activity and numeric extras without truncating a long channel ID',()=>{
  const u=helper().url('1999382889828211628');
  assert.match(u,/^intent:#Intent;action=android\.intent\.action\.VIEW;/);
  assert.match(u,/category=android\.intent\.category\.BROWSABLE;/);
  assert.match(u,/package=com\.ytv\.pronew;/);
  assert.match(u,/component=com\.ytv\.pronew\/dev\.google\.ytvplayer\.ui\.splash\.SplashActivity;/);
  assert.match(u,/l\.id=1999382889828211628;i\.type=1;/);
  assert.match(u,/S\.browser_fallback_url=https%3A%2F%2Ftawasol-alatta\.example%2Fytv-open\.html;end$/);
});
test('Invalid channel identifiers cannot be interpolated into external links',()=>{
  const p=helper();
  for(const bad of ['0','-1','123;end','http://evil', '9223372036854775808', '',null])
    assert.equal(p.intentFor(bad,'https://tawasol-alatta.example'),null);
});
test('Desktop navigates only to a same-origin help page and does not try Android intent',()=>{
  assert.equal(helper('Mozilla/5.0 Windows Chrome').url('1425'),'/ytv-open.html');
});
test('Fallback page links to confirmed Google Play listing and does not equate failure with non-installation',()=>{
  assert.match(help,/https:\/\/play\.google\.com\/store\/apps\/details\?id=com\.ytv\.pronew/);
  assert.match(help,/لكن لا يقبل فتح القنوات من روابط المتصفح/);
  assert.doesNotMatch(help,/apk\s*download|\.apk\b/i);
});
test('Sports cards use direct anchor gestures for YTV without trying browser HLS and retain other channel types',()=>{
  assert.match(modules,/data-ytv-launch/);
  assert.match(modules,/href="\$\{esc\(href\)\}"/);
  assert.match(modules,/AttaYtvWebLaunch\?\.url\(c\.streamUrl\)/);
  assert.doesNotMatch(modules,/bridge\.sportsPlayback\(channel\.id\)/);
  assert.match(modules,/AttaSportsPlayer\.play\(host,url,channel\.format\)/);
  assert.ok(html.indexOf('ytv-web-launch.js?v=14.21') < html.indexOf('web-modules.js?v=14.21'));
});
