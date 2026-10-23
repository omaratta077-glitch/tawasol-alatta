import assert from 'node:assert/strict';
import fs from 'node:fs';
import {JSDOM} from 'jsdom';
const source=fs.readFileSync(new URL('../../cloudflare-site/public/app.js',import.meta.url),'utf8');
const dom=new JSDOM('<label for="ageInput">تاريخ الميلاد</label><input id="ageInput" type="date">',{runScripts:'outside-only'});
const w=dom.window,d=w.document;
const OriginalDate=w.Date;
w.Date=class extends OriginalDate{constructor(...args){super(...(args.length?args:[2026,9,23]));}};
w.eval(source.slice(source.indexOf('function attaCalendarLimit('),source.indexOf('let pendingVerificationId=')));
const input=d.getElementById('ageInput'),group=d.querySelector('.atta-birth-date');
const field=part=>group.querySelector(`[data-birth-part="${part}"]`);
function choose(part,value){field(part).value=value;field(part).dispatchEvent(new w.Event('change'));}
function setDate(y,m,day){choose('year',y);choose('month',m);choose('day',day);}
assert.equal(input.type,'hidden');assert.equal(d.querySelector('label').htmlFor,field('day').id);
assert.throws(()=>w.readBirthDate(input));
setDate('2000','02','29');assert.equal(w.readBirthDate(input),'2000-02-29');
choose('year','2001');assert.equal(field('day').value,'');assert.equal(field('day').options.length,29);assert.throws(()=>w.readBirthDate(input));
setDate('2000','01','31');choose('month','04');assert.equal(field('day').value,'');assert.equal(field('day').options.length,31);
for(const [date,valid] of [['2013-10-23',true],['2013-10-24',false],['1906-10-23',true],['1906-10-22',false]]){
 setDate(...date.split('-'));if(valid)assert.equal(w.readBirthDate(input),date);else assert.throws(()=>w.readBirthDate(input));
}
w.setupBirthDateFields(input);assert.equal(d.querySelectorAll('.atta-birth-date').length,1);
const hydrated=d.createElement('input');hydrated.type='date';hydrated.value='2004-02-29';d.body.append(hydrated);w.setupBirthDateFields(hydrated);assert.equal(w.readBirthDate(hydrated),'2004-02-29');
console.log('PASS birthday required fields, ISO values, leap days, month changes, exact age limits and initial values');w.close();
