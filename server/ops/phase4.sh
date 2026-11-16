#!/usr/bin/env bash
set -euo pipefail
umask 022
test "$EUID" -eq 0
ops=$(cd -- "$(dirname -- "$0")" && pwd)
root=/opt/axis-voice
engine=${1:-piper}
install -d -m 0755 "$root/models" /etc/axis-voice
install -m 0644 "${ops%/*}/axis_voice_service.py" "${ops%/*}/axis_voice_proxy.py" "$root/"
if [[ ! -x $root/.venv/bin/python ]]; then
    python3 -m venv "$root/.venv"
fi

download() {
    local url=$1 path=$2
    if [[ ! -s $path ]]; then
        curl -fsSL --retry 2 --max-time 150 "$url" -o "$path.download"
        mv -T "$path.download" "$path"
    fi
}

case "$engine" in
    piper)
        port=2587
        model=ru_RU-irina-medium.onnx
        upstream=https://huggingface.co/rhasspy/piper-voices/resolve/main/ru/ru_RU/irina/medium
        download "$upstream/$model" "$root/models/$model"
        printf '%s  %s\n' 8ff38212d23da300bbe3705c645e6e5b9475f0bfde01558eb17813e22acaaaaa "$root/models/$model" | sha256sum --check
        download "$upstream/$model.json" "$root/models/$model.json"
        "$root/.venv/bin/pip" install --disable-pip-version-check --only-binary=:all: piper-tts==1.8.0
        ;;
    vosk)
        port=2588
        model=vosk-model-small-ru-0.22
        download "https://alphacephei.com/vosk/models/$model.zip" "${ops%/*}/assets/$model.zip"
        if [[ ! -d $root/models/$model ]]; then
            python3 -m zipfile -e "${ops%/*}/assets/$model.zip" "$root/models"
        fi
        "$root/.venv/bin/pip" install --disable-pip-version-check vosk==0.3.45
        ;;
    *) printf 'Use piper or vosk.\n' >&2; exit 1 ;;
esac
"$root/.venv/bin/pip" install --disable-pip-version-check fastapi==0.162.5 uvicorn==0.54.0 httpx==0.28.1
printf 'AXIS_VOICE_ENGINE=%s\nAXIS_VOICE_PORT=%s\n' "$engine" "$port" > "/etc/axis-voice/$engine.env"
chmod 600 "/etc/axis-voice/$engine.env"
install -m 0644 "$ops/axis-voice@.service" /etc/systemd/system/
systemctl daemon-reload
systemctl enable "axis-voice@$engine"
systemctl restart "axis-voice@$engine"
curl -fsS --max-time 5 --retry 6 --retry-all-errors "http://127.0.0.1:$port/healthz"
printf '\n'
