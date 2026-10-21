"""Exercise the APK checks with real audio bytes and both resource path formats."""
import contextlib
import io
from pathlib import Path
import runpy
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts/check_store_apk.py"
RECORDING = (ROOT / "android-native/app/src/main/res/raw/atta_adhan.ogg").read_bytes()
MANIFEST = '''E: manifest
  E: application
    E: meta-data
      A: android:name="com.alatta.distribution"
      A: android:value="direct"
    E: service (line=1)
      A: android:name="com.alatta.nativeapp.AdhanPlaybackService"
      A: android:exported=(type 0x12)0x0
      A: android:foregroundServiceType=(type 0x11)0x2
'''
PERMISSIONS = "\n".join("uses-permission: name='android.permission." + name + "'" for name in (
    "REQUEST_INSTALL_PACKAGES", "SCHEDULE_EXACT_ALARM", "RECEIVE_BOOT_COMPLETED",
    "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_MEDIA_PLAYBACK", "WAKE_LOCK"))


class StoreApkTest(unittest.TestCase):
    def check(self, path, data=RECORDING, duplicate=False, linked=None):
        with tempfile.TemporaryDirectory() as directory:
            apk_path = Path(directory) / "app.apk"
            with zipfile.ZipFile(apk_path, "w") as archive:
                archive.writestr(path, data)
                if duplicate:
                    archive.writestr("res/second.ogg", data)
                for name in ("privacy.html", "terms.html", "data-rights.html"):
                    archive.writestr("assets/legal/" + name, (ROOT / "cloudflare-site/public" / name).read_bytes())
                archive.writestr("assets/third-party-prayer-licenses.txt", (ROOT / "android-native/app/src/main/assets/third-party-prayer-licenses.txt").read_bytes())

            def aapt(command, **_):
                if "permissions" in command:
                    return PERMISSIONS
                if "xmltree" in command:
                    return MANIFEST
                if "resources" in command:
                    return 'resource 0x7f0f0000 com.alatta.nativeapp:raw/atta_adhan:\n  (string8) "' + (linked or path) + '"\n'
                raise AssertionError("Unexpected aapt command")

            with patch("sys.argv", [str(SCRIPT), str(apk_path), "--channel", "direct"]), \
                 patch("shutil.which", return_value="aapt"), \
                 patch("subprocess.check_output", side_effect=aapt), \
                 contextlib.redirect_stdout(io.StringIO()):
                runpy.run_path(str(SCRIPT), run_name="__main__")

    def test_debug_path(self):
        self.check("res/raw/atta_adhan.ogg")

    def test_optimized_release_path(self):
        self.check("res/uQ.ogg")

    def test_corrupt_recording_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, "Missing or duplicated offline Adhan recording"):
            self.check("res/uQ.ogg", data=b"x" + RECORDING[1:])

    def test_duplicate_recording_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, "Missing or duplicated offline Adhan recording"):
            self.check("res/uQ.ogg", duplicate=True)

    def test_unreferenced_recording_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, "Adhan resource does not reference"):
            self.check("res/uQ.ogg", linked="res/another.ogg")


if __name__ == "__main__":
    unittest.main()
