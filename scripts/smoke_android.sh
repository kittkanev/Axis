#!/usr/bin/env bash
set -eu

status=0
python3 scripts/smoke_assistant.py "$1" || status=$?
adb root
adb wait-for-device
python3 scripts/smoke_overlay.py "$1" || status=$?
exit "$status"
