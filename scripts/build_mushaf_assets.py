"""Rebuild packaged Madani Hafs page art from the pinned MIT-licensed source.
Run: PYTHONPATH=<scour+svgelements install> python3 scripts/build_mushaf_assets.py --source SOURCE_DIR --quran QURAN_JSON
The source directory contains svg/, surah.json, LICENSE and the upstream README.
"""
from pathlib import Path
from concurrent.futures import ProcessPoolExecutor,as_completed
from contextlib import redirect_stdout
from io import StringIO
import argparse,importlib.util,json,gzip,hashlib,math,xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parent.parent
ASSETS=ROOT/'android-native/app/src/main/assets/mushaf'
COMMIT='78d97544bfdc57e9f04bc97ace3f857ed972d772'
SVG='http://www.w3.org/2000/svg';AYAH='https://quranapp.com/svg'

def page_asset(args):
 number,source,tmp=args
 spec=importlib.util.spec_from_file_location('mushaf_optimizer',ROOT/'scripts/vendor/mushaf_optimizer.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
 module.svg_dir=str(Path(source)/'svg');module.output_dir=tmp
 surahs=json.loads((Path(source)/'surah.json').read_text())
 name=f'{number:03}.svg'
 headers=[]
 if not (Path(tmp)/name).exists():
  with redirect_stdout(StringIO()):headers=module.process_file(name,surahs)
 else:
  headers=[s for s in surahs if s['pageNumber']==number]
  if headers and number>2:
   doc=module.minidom.parse(str(Path(source)/'svg'/name))
   headers=module.optimize_standard_page(doc,number,surahs)
   doc.unlink()
 raw=(Path(tmp)/name).read_bytes();doc=ET.fromstring(raw)
 width,height=map(float,doc.get('viewBox').split()[2:])
 markers=doc.find(f'.//{{{SVG}}}g[@id="ayah_markers"]')
 assert markers is not None
 points=[[float(n.get('{'+AYAH+'}x')),float(n.get('{'+AYAH+'}y'))] for n in markers]
 lines=7 if number<=2 else 15;offset=20 if number<=2 else 6
 points.sort(key=lambda p:(math.ceil((p[1]-offset)/((height-offset*2)/lines)),-p[0]))
 # Original path geometry remains from the upstream converter. Only presentation metadata is added.
 text=raw.decode().replace('<svg ', '<svg preserveAspectRatio="none" ',1)
 style='<style>#ayah_markers path{fill:#884300!important}#content path{stroke:#000;stroke-width:.12;stroke-linejoin:round}</style>'
 # Floral end-marker frames are presentation artwork; the original numeral paths stay intact.
 flowers=[]
 for x,y in points:
  coords=[]
  for k in range(97):
   angle=2*math.pi*k/96;radius=8.7+0.65*math.cos(12*angle)
   coords.append(f"{x+radius*math.cos(angle):.3f},{y+radius*math.sin(angle):.3f}")
  flowers.append('<path d="M'+' L'.join(coords)+'Z"/><circle cx="'+str(x)+'" cy="'+str(y)+'" r="7.25"/>')
 ornament='<g fill="none" stroke="#884300" stroke-width=".32">'+''.join(flowers)+'</g>' 
 end=text.index('>')+1
 if text.startswith('<?xml'):end=text.index('>',text.index('<svg '))+1
 text=text[:end]+style+ornament+text[end:]
 output=ASSETS/'pages'/(name+'.gz');payload=text.encode();output.write_bytes(gzip.compress(payload,compresslevel=9,mtime=0))
 return number,{'width':width,'height':height,'markers':points,'headers':headers or [],'sourceSha256':hashlib.sha256((Path(source)/'svg'/name).read_bytes()).hexdigest(),'assetSha256':hashlib.sha256(output.read_bytes()).hexdigest(),'bytes':output.stat().st_size}

def main():
 parser=argparse.ArgumentParser();parser.add_argument('--source',required=True);parser.add_argument('--quran',required=True);parser.add_argument('--work-dir',required=True);args=parser.parse_args()
 ASSETS.mkdir(exist_ok=True);(ASSETS/'pages').mkdir(exist_ok=True);Path(args.work_dir).mkdir(exist_ok=True)
 quran=json.loads(Path(args.quran).read_bytes());assert quran['code']==200
 chapters=quran['data']['surahs'];assert len(chapters)==114
 ayahs=[None];surahs=[]
 for s in chapters:
  surahs.append({'number':s['number'],'name':s['name'],'startPage':s['ayahs'][0]['page'],'numberOfAyahs':len(s['ayahs'])})
  for a in s['ayahs']:
   assert a['number']==len(ayahs)
   ayahs.append({'number':a['number'],'surah':s['number'],'numberInSurah':a['numberInSurah'],'text':a['text'],'page':a['page'],'juz':a['juz'],'hizbQuarter':a['hizbQuarter']})
 assert len(ayahs)==6237
 pages=[None]*605
 with ProcessPoolExecutor(max_workers=4) as pool:
  tasks=[pool.submit(page_asset,(n,args.source,args.work_dir)) for n in range(1,605)]
  for done,future in enumerate(as_completed(tasks),1):
   number,value=future.result();pages[number]=value
   if done%50==0:print('Prepared',done,'/ 604 pages',flush=True)
 count=0;juz=[None]*31;quarters=[None]*241;different_pages=set()
 for n in range(1,605):
  item=pages[n];numbers=[]
  for marker in item['markers']:
   count+=1;assert count<len(ayahs)
   # This printed Madani edition and the API do not share every page break.
   # Use the actual printed endpoints in reading order, never the API page field.
   if ayahs[count]['page']!=n:different_pages.add(n)
   ayahs[count]['page']=n
   marker.insert(0,count);numbers.append(count)
  assert numbers,f'No ayah on page {n}'
  item['ayahs']=numbers
  for number in numbers:
   a=ayahs[number]
   if juz[a['juz']] is None:juz[a['juz']]=n
   if quarters[a['hizbQuarter']] is None:quarters[a['hizbQuarter']]=n
 assert count==6236
 source_surahs=json.loads((Path(args.source)/'surah.json').read_text())
 for s,original in zip(surahs,source_surahs):
  first=next(a for a in ayahs[1:] if a['surah']==s['number'])
  s['startPage']=first['page']
  assert s['numberOfAyahs']==original['ayahCount']
  assert s['startPage']==original['pageNumber'],f'Surah start differs: {s["number"]}'
 assert all(juz[1:]) and all(quarters[1:])
 index={'edition':'مصحف المدينة النبوية — حفص عن عاصم','sourceCommit':COMMIT,'pages':pages,'ayahs':ayahs,'surahs':surahs,'juzStart':juz,'quarterStart':quarters}
 (ASSETS/'index.json').write_text(json.dumps(index,ensure_ascii=False,separators=(',',':')))
 (ASSETS/'LICENSE.txt').write_bytes((Path(args.source)/'LICENSE').read_bytes())
 (ASSETS/'SOURCE.md').write_text('Madani Hafs page art from Batoul Apps Quran SVG, originally converted from Quran Printing Complex artwork.\nSource: https://github.com/batoulapps/quran-svg/tree/'+COMMIT+'\nMIT license included. Optimized with the upstream converter. Original Quran glyph paths and numeral paths are retained. Brown floral frames, a light stroke and viewport fit are presentation changes; these frames are not facsimiles of a particular printed ornament.\nMetadata and verse preview text: https://api.alquran.cloud/v1/quran/quran-uthmani, fetched 2026-10-18. All 6236 endpoints are assigned sequentially from the SVG artwork. All 114 chapter counts and printed chapter-start pages are checked against upstream surah.json. Printed page allocations come from the artwork, not the API; page breaks differ on '+str(len(different_pages))+' pages. No claim is made that every glyph or ornament matches the user screenshot edition pixel-for-pixel.\n')
 print('Verified',count,'ayah markers / 604 pages; packaged bytes',sum(p.stat().st_size for p in ASSETS.rglob('*') if p.is_file()),flush=True)
if __name__=='__main__':main()
