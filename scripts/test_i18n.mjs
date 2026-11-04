import fs from 'node:fs';
import assert from 'node:assert/strict';

const web = fs.readFileSync('cloudflare-site/public/atta-i18n.js','utf8');
const index = fs.readFileSync('cloudflare-site/public/index.html','utf8');
const native = fs.readFileSync('android-native/app/src/main/java/com/alatta/nativeapp/AttaLanguage.kt','utf8');
const main = fs.readFileSync('android-native/app/src/main/java/com/alatta/nativeapp/MainActivity.kt','utf8');
const gradle = fs.readFileSync('android-native/app/build.gradle.kts','utf8');

new Function(web);
assert.ok(index.includes('atta-i18n.js?v=1'),'website loads language bundle');
assert.ok(web.includes("localStorage.setItem(key,lang)"),'web selection persists');
assert.ok(web.includes('document.documentElement.dir'),'website handles RTL/LTR');
assert.ok(web.includes('.quran-text') && web.includes('.message-text'),'religious and user content excluded');
assert.ok(native.includes('getSharedPreferences(PREF') && native.includes('fun toggle(context: Context)'),'Android selection persists');
assert.ok(main.includes('AttaLanguage.initialize(this)'),'Android initializes preference');
assert.ok(main.includes('LocalLayoutDirection provides direction'),'Android handles RTL/LTR');
assert.match(gradle,/compileSdk = 36/);
assert.match(gradle,/targetSdk = 36/);
console.log('PASS Arabic/English UI foundation, preference and API 36');
