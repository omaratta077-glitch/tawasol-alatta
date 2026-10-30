/** اشتق بيانات الموقع من أصول Android المعتمدة التي لم تتغير. بلا تنزيلات عن بُعد. */
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import crypto from 'node:crypto';
import { DatabaseSync } from 'node:sqlite';
import { fileURLToPath } from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const native=path.join(root,'android-native/app/src/main/assets');
const out=path.join(root,'cloudflare-site/public/knowledge/generated');
const azkarSource=JSON.parse(fs.readFileSync(path.join(root,'cloudflare-site/public/knowledge/azkar-source.json'),'utf8'));
if(crypto.createHash('sha256').update(fs.readFileSync(path.join(root,azkarSource.source))).digest('hex')!==azkarSource.sha256)throw Error('Native Azkar changed; regenerate its exact website export before publishing');
fs.mkdirSync(out,{recursive:true});
const write=(file,data)=>{const dest=path.join(out,file);fs.mkdirSync(path.dirname(dest),{recursive:true});fs.writeFileSync(dest,data);};
const manifest=JSON.parse(fs.readFileSync(path.join(native,'hadith/manifest.json'),'utf8'));
const packed=Buffer.concat(manifest.parts.map(p=>fs.readFileSync(path.join(native,'hadith',p.file))));
const dbBytes=zlib.gunzipSync(packed);
if(dbBytes.length!==manifest.dbBytes||crypto.createHash('sha256').update(dbBytes).digest('hex')!==manifest.dbSha256)throw Error('Hadith source checksum mismatch');
const dbPath=path.join(out,'.export.db');fs.writeFileSync(dbPath,dbBytes);
const db=new DatabaseSync(dbPath,{readOnly:true});
const books=db.prepare('SELECT id,title,author,count,source,source_url,edition FROM books ORDER BY ordinal').all();
const size=32;
for(const book of books){
 const rows=db.prepare('SELECT number,section,body,search FROM hadiths WHERE book_id=? ORDER BY id').all(book.id);
 if(rows.length!==book.count)throw Error('Incomplete hadith book '+book.id);
 for(let i=0;i<rows.length;i+=size)write(`hadith/${book.id}/${i/size}.json`,JSON.stringify(rows.slice(i,i+size).map(({search,...row})=>row)));
 write(`hadith/${book.id}/search.json`,JSON.stringify(rows.map(({number,search},i)=>({number,search,page:Math.floor(i/size)}))));
}
write('hadith/books.json',JSON.stringify({size,total:manifest.total,books}));
db.close();fs.rmSync(dbPath);
fs.copyFileSync(path.join(native,'hadith/NOTICE.txt'),path.join(out,'hadith/NOTICE.txt'));
write('quran.html',fs.readFileSync(path.join(native,'atta-quran.html'),'utf8').replace("image.src='/mushaf/'","image.src='mushaf/'").replace("loadPage(page);initIndex();", "const linkedPage=Number(new URLSearchParams(location.search).get('page'));if(Number.isInteger(linkedPage)&&linkedPage>=1&&linkedPage<=604)page=linkedPage;loadPage(page);initIndex();"));
write('mushaf/index.json',fs.readFileSync(path.join(native,'mushaf/index.json')));
for(let p=1;p<=604;p++){const n=String(p).padStart(3,'0');write(`mushaf/${n}.svg`,zlib.gunzipSync(fs.readFileSync(path.join(native,`mushaf/pages/${n}.svg.gz`))));}
for(const f of ['SOURCE.md','LICENSE.txt'])write('mushaf/'+f,fs.readFileSync(path.join(native,'mushaf',f)));
write('fonts/AmiriQuran-Regular.ttf',fs.readFileSync(path.join(native,'fonts/AmiriQuran-Regular.ttf')));
write('fonts/AmiriQuran-OFL.txt',fs.readFileSync(path.join(native,'fonts/AmiriQuran-OFL.txt')));
write('provenance.json',JSON.stringify({hadithSha256:manifest.dbSha256,hadithTotal:manifest.total,quranPages:604,azkarSourceSha256:crypto.createHash('sha256').update(fs.readFileSync(path.join(root,'android-native/app/src/main/java/com/alatta/nativeapp/AzkarCatalog.kt'))).digest('hex')}));
console.log(`PASS: website data derived from unchanged Android assets: ${manifest.total} hadiths, 604 Quran pages`);
