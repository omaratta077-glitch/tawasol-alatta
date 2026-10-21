"""Check the actual merged APK, distribution channel, permissions and legal text."""
import argparse
from pathlib import Path
import re
import json
import shutil
import subprocess
import zipfile
import hashlib

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
for permission in ("SCHEDULE_EXACT_ALARM", "RECEIVE_BOOT_COMPLETED", "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_MEDIA_PLAYBACK", "WAKE_LOCK"):
    assert "android.permission." + permission in permissions, "Missing local Adhan permission: " + permission
assert "android.permission.USE_EXACT_ALARM" not in permissions, "Adhan must use user-approved exact alarm access"
manifest = subprocess.check_output([aapt, "dump", "xmltree", str(args.apk), "AndroidManifest.xml"], text=True)
assert re.search(r'android:name[^\n]*"com.alatta.distribution"[^\n]*\n\s*A: android:value[^\n]*"' + args.channel + '"', manifest), "Wrong distribution metadata"
lines = manifest.splitlines()
playback_service = ""
for index, line in enumerate(lines):
    if "E: service " not in line:
        continue
    indentation = len(line) - len(line.lstrip())
    block = [line]
    for following in lines[index + 1:]:
        if "E: " in following and len(following) - len(following.lstrip()) <= indentation:
            break
        block.append(following)
    if "AdhanPlaybackService" in "\n".join(block):
        playback_service = "\n".join(block)
        break
assert playback_service, "Missing local Adhan playback service"
assert re.search(r'android:exported[^\n]*\b0x0\b', playback_service), "Adhan service must not be exported"
assert re.search(r'android:foregroundServiceType[^\n]*\b0x2\b', playback_service), "Adhan service must declare mediaPlayback"
with zipfile.ZipFile(args.apk) as apk:
    audio = [name for name in apk.namelist() if name.endswith("/atta_adhan.ogg")]
    assert len(audio) == 1, "Missing or duplicated offline Adhan recording"
    recording = apk.read(audio[0])
    assert len(recording) == 1229032 and hashlib.sha256(recording).hexdigest() == "35fe06b08fe80505c550c33fed8a783fa9901ddc81ac884958b4be048f5b2a79", "Offline Adhan recording integrity failed"
    assert b"Copyright (c) 2016 Batoul Apps" in apk.read("assets/third-party-prayer-licenses.txt"), "Missing prayer calculation license"
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
