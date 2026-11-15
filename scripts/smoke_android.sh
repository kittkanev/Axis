#!/usr/bin/env bash
set -eu

status=0
python3 scripts/smoke_assistant.py "$1" || status=$?
adb root
adb wait-for-device
adb shell pm clear io.github.kittkanev.axis
python3 scripts/smoke_overlay.py "$1" || status=$?
exit "$status"
