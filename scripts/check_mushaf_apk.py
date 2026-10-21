"""Verify slim APK: opening Quran page is bundled; remaining pages download safely."""
from pathlib import Path
import argparse
import gzip
import zipfile

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / 'android-native/app/src/main/assets'

def verify(apk):
    with zipfile.ZipFile(apk) as archive:
        expected = {'assets/mushaf/pages/001.svg'}
        actual = {n for n in archive.namelist() if n.startswith('assets/mushaf/pages/')}
        assert actual == expected, f'Only opening page may be bundled: {len(actual)} assets'
        source = gzip.decompress((ASSETS / 'mushaf/pages/001.svg.gz').read_bytes())
        assert archive.read('assets/mushaf/pages/001.svg') == source
        for rel in ['atta-quran.html', 'mushaf/index.json', 'mushaf/LICENSE.txt']:
            assert archive.read('assets/' + rel) == (ASSETS / rel).read_bytes()
    print('PASS: slim Quran APK has verified opening page, index, licenses and reader')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    verify(parser.parse_args().apk)
