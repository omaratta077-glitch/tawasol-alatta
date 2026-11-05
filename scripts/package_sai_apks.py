#!/usr/bin/env python3
"""Wrap an already signed universal APK in a SAI-compatible APKS archive.

This never changes APK bytes, signing, application ID, or application resources.
"""
import argparse
import json
import re
import time
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZIP_STORED, ZipFile, is_zipfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--package", required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--code", type=int, required=True)
    parser.add_argument("--min-sdk", type=int, required=True)
    parser.add_argument("--target-sdk", type=int, required=True)
    options = parser.parse_args()

    if not options.apk.is_file() or not is_zipfile(options.apk):
        parser.error("The input must be an existing Android APK (ZIP).")
    if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+", options.package):
        parser.error("Invalid Android package name.")
    if options.code <= 0 or not options.version or options.min_sdk > options.target_sdk:
        parser.error("Invalid APK version or SDK.")
    if options.apk.resolve() == options.output.resolve():
        parser.error("Output must differ from the input APK.")

    stamped = {
        "export_timestamp": int(time.time() * 1000),
        "label": options.label,
        "package": options.package,
        "version_code": options.code,
        "version_name": options.version,
    }
    meta_v2 = {
        **stamped,
        "backup_components": [{"size": options.apk.stat().st_size, "type": "apk_files"}],
        "split_apk": False,
        "meta_version": 2,
        "min_sdk": options.min_sdk,
        "target_sdk": options.target_sdk,
    }
    options.output.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(options.output, "w") as archive:
        archive.writestr("meta.sai_v1.json", json.dumps(stamped, ensure_ascii=False), compress_type=ZIP_DEFLATED)
        archive.writestr("meta.sai_v2.json", json.dumps(meta_v2, ensure_ascii=False), compress_type=ZIP_DEFLATED)
        archive.write(options.apk, "base.apk", compress_type=ZIP_STORED)

    with ZipFile(options.output) as archive:
        assert archive.namelist() == ["meta.sai_v1.json", "meta.sai_v2.json", "base.apk"]
        assert archive.getinfo("base.apk").file_size == options.apk.stat().st_size
    print(f"SAI archive ready: {options.output}")


if __name__ == "__main__":
    main()
