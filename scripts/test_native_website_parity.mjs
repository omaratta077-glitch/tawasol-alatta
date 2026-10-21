import fs from 'node:fs';
import assert from 'node:assert/strict';
const css=fs.readFileSync('cloudflare-site/public/style.css','utf8');
const colors=fs.readFileSync('android-native/app/src/main/java/com/alatta/nativeapp/WebsiteStyle.kt','utf8');
const chrome=fs.readFileSync('android-native/app/src/main/java/com/alatta/nativeapp/PremiumChrome.kt','utf8');
const main=fs.readFileSync('android-native/app/src/main/java/com/alatta/nativeapp/MainActivity.kt','utf8');
for (const [name,key] of Object.entries({
    black:'--g13-black',panel:'--g13-panel',panel2:'--g13-panel2',
    goldDark:'--g13-gold-dark',gold:'--g13-gold',goldMid:'--g13-gold-mid',
    goldLight:'--g13-gold-light',goldPale:'--g13-gold-pale'})) {
    const value=css.match(new RegExp(key+':#([0-9A-Fa-f]{6});'))?.[1];
    assert.ok(value && colors.includes('val '+name+' = Color(0xFF'+value.toUpperCase()+')'),key+' parity');
}
assert.ok(chrome.includes('ProfileMediaImage(avatarUrl') &&
    main.includes('avatarUrl = myAvatar') && main.includes('accountName = displayName'),
    'real profile identity in native website header');
const nav=chrome.slice(chrome.indexOf('val destinations = listOf('));
const pos=['"الرئيسية"','"استكشاف"','"الأصدقاء"','"الرسائل"','"الإشعارات"','"الملف"']
    .map(s=>nav.indexOf('Triple('+s));
assert.ok(pos.every(x=>x>=0) && pos.every((x,i)=>i===0||x>pos[i-1]),'same social nav order as website');
for (const name of ['"القرآن"','"الأحاديث"','"الأذكار"','"الرياضة"','"يوتيوب"'])
    assert.ok(nav.includes(name),'native module preserved '+name);
assert.ok(main.includes('WebsiteFeedPostCard {'),'website-style post cards');
console.log('PASS website palette and native social shell parity');
