"""Verify the actual Android package after asset preprocessing, before release."""
from pathlib import Path
import argparse
import gzip
import zipfile

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / 'android-native/app/src/main/assets'


def verify(apk):
    with zipfile.ZipFile(apk) as archive:
        expected = {f'assets/mushaf/pages/{p:03}.svg' for p in range(1, 605)}
        actual = {n for n in archive.namelist() if n.startswith('assets/mushaf/pages/')}
        assert actual == expected, 'APK must contain all 604 plain SVG paths used by the native reader'
        # Android expands source .svg.gz assets during merge. Compare final bytes.
        for p in range(1, 605):
            original = gzip.decompress((ASSETS / f'mushaf/pages/{p:03}.svg.gz').read_bytes())
            assert archive.read(f'assets/mushaf/pages/{p:03}.svg') == original, f'Page {p} differs in APK'
        for rel in ['atta-quran.html', 'mushaf/index.json', 'mushaf/LICENSE.txt']:
            assert archive.read('assets/' + rel) == (ASSETS / rel).read_bytes(), f'APK asset differs: {rel}'
    print('PASS: final APK contains 604 exact printed pages, reader, index and license at the native asset paths')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    verify(parser.parse_args().apk)
