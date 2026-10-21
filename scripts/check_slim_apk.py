"""Guard the actual release APK size, phone architectures and slim Quran assets."""
import argparse
from pathlib import Path
import zipfile

from check_mushaf_apk import verify


def check(apk):
    apk = Path(apk)
    verify(apk)
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        abis = {name.split('/')[1] for name in names if name.startswith('lib/') and name.endswith('.so')}
        assert abis == {'arm64-v8a', 'armeabi-v7a'}, f'Release must retain both ARM phone architectures: {abis}'
        for abi in abis:
            assert f'lib/{abi}/libjingle_peerconnection_so.so' in names, f'Missing native call engine: {abi}'
        assert apk.stat().st_size < 60_000_000, 'Slim release APK exceeded the 60 MB size budget'
    print(f'PASS: slim release APK {apk.stat().st_size:,} bytes; both ARM call engines present; no x86 emulator libraries')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    check(parser.parse_args().apk)
