"""Check the actual merged APK, distribution channel, permissions and legal text."""
import argparse
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("apk", type=Path)
parser.add_argument("--channel", choices=("direct", "appgallery"), required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
aapt = shutil.which("aapt")
if not aapt:
    import os
    sdk = Path(os.environ.get("ANDROID_HOME", ""))
    candidates = sorted(sdk.glob("build-tools/*/aapt"))
    aapt = str(candidates[-1]) if candidates else None
if not aapt:
    raise SystemExit("Android aapt is required to verify the merged APK")
permissions = subprocess.check_output([aapt, "dump", "permissions", str(args.apk)], text=True)
install_permission = "android.permission.REQUEST_INSTALL_PACKAGES" in permissions
assert install_permission == (args.channel == "direct"), "Incorrect installer permission for this distribution"
manifest = subprocess.check_output([aapt, "dump", "xmltree", str(args.apk), "AndroidManifest.xml"], text=True)
assert re.search(r'android:name[^\n]*"com.alatta.distribution"[^\n]*\n\s*A: android:value[^\n]*"' + args.channel + '"', manifest), "Wrong distribution metadata"
with zipfile.ZipFile(args.apk) as apk:
    for name in ("privacy.html", "terms.html", "data-rights.html"):
        assert apk.read("assets/legal/" + name) == (root / "cloudflare-site/public" / name).read_bytes(), "Legal text differs from the published source: " + name
    assert "2026-10-20" in apk.read("assets/legal/privacy.html").decode(), "Missing current privacy version"
print(f"PASS: actual {args.channel} APK permissions, channel and offline legal documents")
