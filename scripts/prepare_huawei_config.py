"""Prepare the official client config without printing it or server credentials."""
import json
import os
from pathlib import Path

raw = os.environ.get("ALATTA_HMS_AGCONNECT_JSON", "").strip()
if raw:
    config = json.loads(raw)
    client = config.get("client", {})
    if client.get("package_name") != "com.alatta.nativeapp":
        raise SystemExit("Huawei client config has the wrong Android package")
    app_id = str(client.get("app_id", ""))
    if not app_id.isdigit():
        raise SystemExit("Huawei client config is missing a valid app_id")
    expected = os.environ.get("ALATTA_HMS_APP_ID", "").strip()
    if expected and expected != app_id:
        raise SystemExit("Huawei app ID and client configuration do not match")
    target = Path("android-native/app/src/main/assets/agconnect-services.json")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(config, ensure_ascii=False))
    if os.environ.get("GITHUB_ENV"):
        with open(os.environ["GITHUB_ENV"], "a") as output:
            output.write(f"ALATTA_HMS_APP_ID={app_id}\n")
    print("Huawei client configuration prepared for the correct Android package.")
else:
    print("Huawei Push client configuration not supplied. HMS activation still requires the developer account configuration.")
