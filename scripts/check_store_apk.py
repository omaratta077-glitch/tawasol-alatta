"""Check the actual merged APK, distribution channel, permissions and legal text."""
import argparse
from pathlib import Path
import re
import json
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
    if "assets/agconnect-services.json" in apk.namelist():
        def check_public(value):
            if isinstance(value, dict):
                for key, item in value.items():
                    assert not any(word in re.sub(r"[^a-z]", "", key.lower()) for word in ("secret", "privatekey", "accesstoken", "password")), "Server credential found in client config"
                    check_public(item)
            elif isinstance(value, list):
                for item in value: check_public(item)
        check_public(json.loads(apk.read("assets/agconnect-services.json")))
print(f"PASS: actual {args.channel} APK permissions, channel and offline legal documents")
