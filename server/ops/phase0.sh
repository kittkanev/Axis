#!/usr/bin/env bash
set -euo pipefail

if (( EUID != 0 )); then
    printf 'Run this script as root.\n' >&2
    exit 1
fi

ops=$(cd -- "$(dirname -- "$0")" && pwd)
export DEBIAN_FRONTEND=noninteractive
export NEEDRESTART_MODE=l
export LC_ALL=C

ensure_file() {
    local source=$1 destination=$2
    if [[ -e $destination ]] && ! cmp -s "$source" "$destination"; then
        printf 'Existing configuration differs: %s\n' "$destination" >&2
        exit 1
    fi
    install -D -m 0644 "$source" "$destination"
}

ssh_port=$(awk '{print $4}' <<< "${SSH_CONNECTION:-}")
if [[ $ssh_port != 22 ]]; then
    printf 'Expected a live SSH session on guest port 22.\n' >&2
    exit 1
fi

install -d -m 0755 /etc/axis
if ! getent passwd axis >/dev/null; then
    useradd --system --user-group --home-dir /var/lib/axis --shell /usr/sbin/nologin axis
fi
install -d -m 0750 -o axis -g axis /var/lib/axis

if [[ ! -e /swapfile ]]; then
    install -m 0600 /dev/null /swapfile
    dd if=/dev/zero of=/swapfile bs=1M count=2048 status=none
    mkswap /swapfile
fi
if [[ $(stat -c '%u:%a:%s' /swapfile) != 0:600:2147483648 || $(blkid -s TYPE -o value /swapfile) != swap ]]; then
    printf 'Check existing /swapfile before enabling swap.\n' >&2
    exit 1
fi
if ! swapon --noheadings --raw --show=NAME | grep -Fxq /swapfile; then
    swapon /swapfile
fi
if ! awk '$1 == "/swapfile" && $3 == "swap" { found = 1 } END { exit !found }' /etc/fstab; then
    printf '\n/swapfile none swap sw 0 0\n' >> /etc/fstab
fi
ensure_file "$ops/axis-swappiness.conf" /etc/sysctl.d/90-axis-swappiness.conf
sysctl -p /etc/sysctl.d/90-axis-swappiness.conf

# Open the port seen inside the VM before enabling the firewall.
systemctl unmask ufw.service
ufw allow 22/tcp comment 'Axis VPS SSH'
ufw default deny incoming
ufw default allow outgoing
ufw --force enable
systemctl enable ufw.service
ufw status verbose

apt-get update
apt-get -y --no-remove -o Dpkg::Options::=--force-confold upgrade
apt-get install -y --no-remove --no-install-recommends ca-certificates curl gnupg \
    fail2ban python3-systemd sqlite3 unattended-upgrades

# Caddy's official signed APT repository for Debian and Ubuntu.
keyring=/usr/share/keyrings/caddy-stable-archive-keyring.gpg
repo=/etc/apt/sources.list.d/caddy-stable.list
if [[ ! -e $keyring ]]; then
    curl -fsSL --retry 2 https://dl.cloudsmith.io/public/caddy/stable/gpg.key | gpg --dearmor -o "$keyring"
    chmod 0644 "$keyring"
fi
if [[ ! -e $repo ]]; then
    curl -fsSL --retry 2 https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt -o "$repo"
    chmod 0644 "$repo"
fi

ensure_file "$ops/Caddyfile.local" /etc/caddy/axis.Caddyfile
ensure_file "$ops/caddy.limits.conf" /etc/systemd/system/caddy.service.d/axis.conf
ensure_file "$ops/fail2ban-axis.local" /etc/fail2ban/jail.d/axis.local
ensure_file "$ops/fail2ban.limits.conf" /etc/systemd/system/fail2ban.service.d/axis.conf
apt-get update
apt-get install -y --no-remove --no-install-recommends caddy

runuser -u caddy -- env HOME=/var/lib/caddy caddy validate --config /etc/caddy/axis.Caddyfile --adapter caddyfile
fail2ban-client -t
systemctl daemon-reload
systemctl enable --now ufw caddy fail2ban
systemctl restart caddy fail2ban
systemctl enable --now apt-daily.timer apt-daily-upgrade.timer unattended-upgrades.service

systemctl --no-pager --lines=0 status caddy fail2ban ssh unattended-upgrades
fail2ban-client status sshd
curl -fsS --retry 4 --retry-all-errors --max-time 5 --cacert /var/lib/caddy/.local/share/caddy/pki/authorities/local/root.crt https://localhost:8443/proxy-healthz
printf '\n'
free -m
df -h /
