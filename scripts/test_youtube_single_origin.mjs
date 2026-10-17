import fs from 'node:fs';
import assert from 'node:assert/strict';
import vm from 'node:vm';
const main=fs.readFileSync('android-native/app/src/main/java/com/alatta/nativeapp/MainActivity.kt','utf8');
const shared=fs.readFileSync('cloudflare-site/public/youtube-player-shared.js','utf8');
const template=main.match(/val injectPlayer = remember\(safeId, sharedJs\) \{\s*"""([\s\S]*?)"""\s*\}/)?.[1];
assert.ok(template,'Native injected player source exists');
const id='dQw4w9WgXcQ';
const javascript=template.replace('$sharedJs',shared).replace('$safeId',id);
new vm.Script(javascript);
const frames=[];
const host={style:{},appendChild(node){this.child=node;}};
const document={
  documentElement:{style:{}}, body:{style:{},replaceChildren(node){this.child=node;}},
  createElement(tag){
    if(tag==='div')return host;
    assert.equal(tag,'iframe');
    const f={style:{},addEventListener(){},set referrerPolicy(v){this._referrer=v;}};
    frames.push(f);
    return f;
  }
};
const location={origin:'https://tawasol-alatta.omaratta077-4fd.workers.dev'};
const window={};
const logs=[];
const context=vm.createContext({window,document,location,console:{log(v){logs.push(v)}},encodeURIComponent});
vm.runInContext(javascript,context);
assert.deepEqual(logs,[],`Browser console reported errors: ${logs.join(' ')}`);
assert.equal(frames.length,1,'Exactly one YouTube iframe');
assert.equal(host.child,frames[0]);
assert.equal(document.body.child,host);
assert.ok(frames[0].src.includes(`/embed/${id}?`));
assert.ok(frames[0].src.includes('origin='+encodeURIComponent(location.origin)));
assert.equal(frames[0]._referrer,'strict-origin-when-cross-origin');
assert.ok(main.includes('loadUrl("$BASE/")'));
assert.ok(!main.includes('new YT.Player('));
assert.ok(!main.includes('loadDataWithBaseURL('));
assert.ok(!main.includes('OutlinedTextField(search, { search = it }, label = { Text("ابحث بالاسم أو اسم المستخدم") }'));
assert.ok(main.includes('modifier = modifier.height(39.dp)'));
console.log('PASS: real website origin; one iframe from website factory; 39dp search; no duplicate friends search');
