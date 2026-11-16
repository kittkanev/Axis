#!/usr/bin/env bash
set -euo pipefail
umask 022
export DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=l
ops=$(cd -- "$(dirname -- "$0")" && pwd)
source_dir=$(dirname -- "$ops")
test "$EUID" -eq 0
getent passwd axis >/dev/null

if ! dpkg-query -W -f='${db:Status-Status}' python3-venv 2>/dev/null | grep -qx installed; then
    apt-get install -y --no-remove --no-install-recommends python3-venv
fi
install -d -m 0755 /opt/axis /etc/axis /var/lib/axis-updates
install -d -o axis -g axis -m 0750 /var/lib/axis
if [[ ! -e /etc/axis/.env ]]; then
    (umask 077; set -o noclobber; printf 'AXIS_JWT_KEY=%s\n' "$(openssl rand -hex 32)" > /etc/axis/.env)
fi
[[ $(stat -c '%u:%a' /etc/axis/.env) == 0:600 ]]

if [[ ! -x /opt/axis/.venv/bin/python ]]; then
    python3 -m venv /opt/axis/.venv
fi
/opt/axis/.venv/bin/python -m pip install --disable-pip-version-check -r "$source_dir/requirements.txt"
runuser -u axis -- /opt/axis/.venv/bin/python -c 'from httpx import Client, AsyncClient; from trafilatura import extract'
for module in "$source_dir"/axis_*.py; do
    /opt/axis/.venv/bin/python -m py_compile "$module"
    install -m 0644 "$module" "/opt/axis/$(basename "$module")"
done
install -m 0755 "$ops/backup.sh" /usr/local/sbin/axis-backup
install -m 0644 "$ops/configure-boosty.py" /opt/axis/configure-boosty.py
install -m 0644 "$ops/axis.service" "$ops/axis-backup.service" "$ops/axis-backup.timer" /etc/systemd/system/
systemctl daemon-reload
systemctl enable axis.service
systemctl restart axis.service
systemctl enable --now axis-backup.timer
curl -fsS --retry 5 --retry-all-errors --max-time 6 --cacert /var/lib/caddy/.local/share/caddy/pki/authorities/local/root.crt https://localhost:8443/healthz
printf '\n'
systemctl --no-pager --lines=0 status axis.service axis-backup.timer
