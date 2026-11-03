import fs from 'node:fs';
import assert from 'node:assert/strict';
const main=fs.readFileSync('android-native/app/src/main/java/com/alatta/nativeapp/MainActivity.kt','utf8');
const player=fs.readFileSync('android-native/app/src/main/java/com/alatta/nativeapp/AttaFreshYouTubePlayer.kt','utf8');
const reels=fs.readFileSync('android-native/app/src/main/java/com/alatta/nativeapp/AttaFreshReelsViewer.kt','utf8');
const original=fs.readFileSync('android-native/app/src/main/java/com/alatta/nativeapp/AttaExpandableVideo.kt','utf8');
const shared=fs.readFileSync('cloudflare-site/public/youtube-player-shared.js','utf8');
const site=fs.readFileSync('cloudflare-site/public/youtube-section.js','utf8');

assert.equal((main.match(/fun YouTubePlayer\(/g)||[]).length,1,'one native public YouTube entry point');
assert.match(main,/AttaFreshYouTubePlayer\(id, onMiniDrag, portrait, videoIsShort,/);
assert.match(main,/if \(tab == "ريلز" && youtubePlayRequested\) youtubePlayRequested = false/);
assert.ok(!main.includes('loadUrl("$BASE/")'),'no SPA or login page loaded for YouTube video');
assert.ok(!main.includes('atta-site-youtube-bridge.js'),'old site-control bridge disconnected');
assert.ok(!main.includes('new YT.Player('),'no second player in MainActivity');

assert.ok(player.includes('loadDataWithBaseURL(ATTA_API_BASE + "/"'),'clean standalone player with trusted site origin');
assert.ok(player.includes('https://www.youtube.com/iframe_api'),'official IFrame API');
assert.equal((player.match(/new YT.Player\(/g)||[]).length,1);
assert.ok(player.includes('mediaPlaybackRequiresUserGesture = true'),'do not bypass user gesture');
assert.ok(player.includes("onReady:function(){emit('FRAME_LOADED')}"),'video signals readiness via real IFrame API');
assert.ok(player.includes('web.evaluateJavascript("window.attaCommand('),'reels can pause/resume official iframe');
assert.ok(player.includes('chrome.release()') && player.includes('web.destroy()'),'WebView cleaned up');
assert.ok(player.includes('Lifecycle.Event.ON_STOP'),'video pauses on background');
assert.ok(player.includes('widget_referrer')===false,'no made-up referrer bypass');

assert.ok(original.includes('AttaFreshReelsViewer('),'all reels use fresh renderer');
assert.ok(reels.includes('ExoPlayer.Builder(context)'),'uploaded videos use Media3');
assert.equal((reels.match(/ExoPlayer.Builder\(/g)||[]).length,1,'only one Media3 player allocated for reels');
assert.ok(reels.includes('onRefreshReels?.invoke()') && reels.includes('onLoadMoreReels?.invoke()'),'Reels pagination and refresh');
assert.ok(reels.includes('youtubeShortContent(youtubeId'),'Reels use the same official YouTube player');
assert.ok(reels.includes('SocialVideoPostEmbed(social'),'public social clips still supported');
assert.ok(reels.includes('PostInteractionsSheet('),'comments/reactions stay in-app');

assert.ok(site.includes('window.AttaYouTubeEmbed.create'),'website player remains intact');
assert.ok(shared.includes('global.AttaYouTubeEmbed=Object.freeze({create})'));
assert.ok(main.includes('modifier = modifier.height(39.dp)'),'39dp search preserved');
console.log('PASS: freshly rebuilt native YouTube IFrame player and Reels viewer; website unaffected');
