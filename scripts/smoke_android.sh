#!/usr/bin/env bash
set -eu

status=0
adb install --no-incremental -r "$1"
adb shell cmd locale set-app-locales "${AXIS_SMOKE_PACKAGE:-io.github.kittkanev.axis}" --user 0 --locales ru-RU
python3 scripts/smoke_assistant.py "$1" || status=$?
adb root
adb wait-for-device
python3 scripts/smoke_overlay.py "$1" || status=$?
exit "$status"
