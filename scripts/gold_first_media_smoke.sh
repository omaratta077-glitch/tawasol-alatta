#!/usr/bin/env bash
# Runs inside android-emulator-runner AFTER connectedDebugAndroidTest.
# This checks the actual Media MainActivity under the :media process, not Gold
# proprietary binary playback. No user accounts or private network access.
set -euo pipefail

PACKAGE=com.alatta.nativeapp
ACTIVITY=com.alatta.nativeapp.MainActivity
PROCESS="${PACKAGE}:media"

adb shell am force-stop "$PACKAGE"
adb logcat -c
echo "[Gold-first] Starting Media MainActivity in isolated :media process"
adb shell am start -W -n "${PACKAGE}/${ACTIVITY}"
sleep 8

PID="$(adb shell pidof "$PROCESS" | tr -d '\r' || true)"
if [[ -z "$PID" ]]; then
    echo "ERROR: Media :media process is not alive after MainActivity launch"
    adb logcat -d -t 600 | grep -E -A 18 'FATAL EXCEPTION|AndroidRuntime|Unable to start activity|Process: com.alatta.nativeapp' | tail -n 110 || true
    exit 1
fi

if adb logcat -d -t 600 | grep -E -q 'FATAL EXCEPTION:.*|Process: com.alatta.nativeapp:media.*'; then
    # Android may log process startup metadata normally, so fail only if a
    # fatal exception occurs in a context that names the isolated process.
    if adb logcat -d -t 600 | grep -E -A 7 'FATAL EXCEPTION:' | grep -q 'Process: com.alatta.nativeapp:media'; then
        echo 'ERROR: AndroidRuntime crash in Media :media'
        adb logcat -d -t 600 | grep -E -A 20 'FATAL EXCEPTION:' | tail -n 100 || true
        exit 1
    fi
fi
echo "[Gold-first] Media MainActivity process alive: $PID"
