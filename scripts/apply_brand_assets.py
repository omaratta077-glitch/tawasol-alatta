#!/usr/bin/env python3
"""Apply the reviewed gold logo package to existing application assets.
Only the 22 known PNG resources are replaced, and the Huawei store image is added.
No app logic, signing, secrets, or authentication settings are touched.
"""
from pathlib import Path
from hashlib import sha256
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parent.parent
ARCHIVE = ROOT / "branding-assets-upload.zip"
EXPECTED_SHA256 = "9d72071aa2940ae3bc35f02895b0bea58e089d3bb110aef12e7f31b9a258be4f"
PREFIXES = (
    "android-native/app/src/main/res/drawable/",
    "android-native/app/src/main/res/drawable-",
    "android-native/app/src/main/res/mipmap-",
    "cloudflare-site/public/logo-premium.png",
    "cloudflare-site/public/icons/",
)
STORE_IMAGE = "HUAWEI_AppGallery_Icon_512x512.png"

def main() -> None:
    if not ARCHIVE.is_file():
        raise SystemExit("Upload branding-assets-upload.zip to the PR branch before running this script.")
    digest = sha256(ARCHIVE.read_bytes()).hexdigest()
    if digest != EXPECTED_SHA256:
        raise SystemExit("Logo ZIP checksum mismatch; expected the reviewed asset pack, refusing to write.")
    with ZipFile(ARCHIVE) as package:
        items = package.namelist()
        logos = sorted(n for n in items if n.endswith(".png") and n != STORE_IMAGE)
        if len(logos) != 22 or len(set(logos)) != 22:
            raise SystemExit("Expected exactly 22 existing logo/icon resources.")
        for name in logos:
            if not name.startswith(PREFIXES) or not (ROOT / name).is_file():
                raise SystemExit("Unexpected or missing canonical asset: " + name)
        if STORE_IMAGE not in items:
            raise SystemExit("AppGallery 512px store logo missing.")
        for name in logos + [STORE_IMAGE]:
            data = package.read(name)
            if not data.startswith(b"\x89PNG\r\n\x1a\n"):
                raise SystemExit("Invalid PNG in logo package: " + name)
        for name in logos:
            (ROOT / name).write_bytes(package.read(name))
        store_path = ROOT / "branding" / "HUAWEI_AppGallery_Icon_512x512.png"
        store_path.parent.mkdir(parents=True, exist_ok=True)
        store_path.write_bytes(package.read(STORE_IMAGE))
    print("Replaced 22 official brand assets and added the AppGallery 512px artwork.")

if __name__ == "__main__":
    main()
