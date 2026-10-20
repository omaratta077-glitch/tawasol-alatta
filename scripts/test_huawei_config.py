import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parent / "prepare_huawei_config.py"

class HuaweiConfigurationTest(unittest.TestCase):
    def prepare(self, config, expected=""):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        env = {**os.environ, "ALATTA_HMS_AGCONNECT_JSON": json.dumps(config),
               "ALATTA_HMS_APP_ID": expected, "GITHUB_ENV": ""}
        result = subprocess.run([sys.executable, str(SCRIPT)], cwd=directory.name,
                                env=env, capture_output=True, text=True)
        return result, Path(directory.name) / "android-native/app/src/main/assets/agconnect-services.json"

    def test_public_client_configuration_never_contains_server_secrets(self):
        result, path = self.prepare({"client": {"app_id": "123456789", "package_name": "com.alatta.nativeapp",
            "api_key": "public-restricted-client-key", "client_secret": "MUST-NOT-SHIP"},
            "nested": [{"push_app_secret": "MUST-NOT-SHIP", "private_key": "MUST-NOT-SHIP", "region": "DE"}]})
        self.assertEqual(0, result.returncode, result.stderr)
        text = path.read_text()
        self.assertNotIn("MUST-NOT-SHIP", text + result.stdout + result.stderr)
        self.assertEqual("123456789", json.loads(text)["client"]["app_id"])
        self.assertEqual("public-restricted-client-key", json.loads(text)["client"]["api_key"])

    def test_wrong_package_or_app_id_is_rejected_before_writing(self):
        for config, expected in [
            ({"client": {"app_id": "123", "package_name": "wrong.package"}}, ""),
            ({"client": {"app_id": "123", "package_name": "com.alatta.nativeapp"}}, "456"),
            ({"client": {"app_id": "١٢٣", "package_name": "com.alatta.nativeapp"}}, "")]:
            with self.subTest(config=config):
                result, path = self.prepare(config, expected)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(path.exists())

if __name__ == "__main__":
    unittest.main()
