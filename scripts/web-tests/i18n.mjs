import assert from 'node:assert/strict';
import fs from 'node:fs';
import {JSDOM} from 'jsdom';

const read=n=>fs.readFileSync(new URL('../../cloudflare-site/public/'+n,import.meta.url),'utf8');
const en=JSON.parse(read('i18n/en.json'));
const dom=new JSDOM(`<!doctype html><html lang="ar" dir="rtl"><body>
  <select data-atta-language><option value="ar">العربية</option><option value="en">English</option></select>
  <button id="home">الرئيسية</button>
  <input id="search" placeholder="ابحث عن الأشخاص أو المنشورات...">
  <article class="post-text" id="post">الرئيسية</article>
</body></html>`,{url:'https://unit.test/',runScripts:'outside-only'});
const w=dom.window,d=w.document;
w.fetch=async()=>({ok:true,json:async()=>en});
w.eval(read('i18n.js'));
d.dispatchEvent(new w.Event('DOMContentLoaded'));
const settle=()=>new Promise(resolve=>setTimeout(resolve,85));
await settle();
assert.equal(d.documentElement.lang,'ar');
assert.equal(d.querySelector('#home').textContent,'الرئيسية');
d.querySelector('[data-atta-language]').value='en';
d.querySelector('[data-atta-language]').dispatchEvent(new w.Event('change',{bubbles:true}));
await settle();
assert.equal(d.documentElement.lang,'en');
assert.equal(d.documentElement.dir,'ltr');
assert.equal(d.querySelector('#home').textContent,'Home');
assert.equal(d.querySelector('#search').getAttribute('placeholder'),'Search people or posts...');
assert.equal(d.querySelector('#post').textContent,'الرئيسية','post content must never be translated');
const next=d.createElement('button');next.textContent='حفظ';d.body.append(next);
await settle();
assert.equal(next.textContent,'Save','new interface nodes are localized');
w.AttaI18n.setLanguage('ar');await settle();
assert.equal(d.documentElement.dir,'rtl');
assert.equal(d.querySelector('#home').textContent,'الرئيسية');
assert.equal(next.textContent,'حفظ');
w.close();
console.log('PASS site bilingual selector, persistence semantics, RTL/LTR and safe dynamic labels');
