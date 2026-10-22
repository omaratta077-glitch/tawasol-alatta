"""Keep website SVG paths and paint identical to canonical Android drawables."""
from pathlib import Path
import html,json,sys,xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1]
a='{http://schemas.android.com/apk/res/android}'
icons={}
def paint(value):
    if value in ('#00000000','@android:color/transparent'):return 'none'
    return 'currentColor'
for file in sorted((root/'android-native/app/src/main/res/drawable').glob('atta_ic_*.xml')):
    vector=ET.parse(file).getroot();paths=[]
    for path in vector.iter('path'):
        attrs={'d':path.get(a+'pathData'),'fill':paint(path.get(a+'fillColor','#FF000000')),'stroke':'none'}
        if path.get(a+'strokeColor'):
            attrs.update(stroke=paint(path.get(a+'strokeColor')))
            for source,target in [('strokeWidth','stroke-width'),('strokeLineCap','stroke-linecap'),('strokeLineJoin','stroke-linejoin')]:
                if path.get(a+source):attrs[target]=path.get(a+source)
        paths.append('<path '+' '.join(k+'="'+html.escape(v,quote=True)+'"' for k,v in attrs.items())+'/>')
    icons[file.stem[8:].replace('_','-')]=''.join(paths)
file=root/'cloudflare-site/public/icons.js';source=file.read_text();start=source.index('const icons=');end=source.index('\n\nfunction icon(',start)
updated=source[:start]+'const icons='+json.dumps(icons,ensure_ascii=False,indent=2)+';'+source[end:]
if '--check' in sys.argv:
    if source!=updated:raise SystemExit('FAIL website icons differ from Android; run scripts/export_web_icons.py')
else:file.write_text(updated)
print('PASS exact Android icon geometry, fill and stroke:',len(icons))
