#!/usr/bin/env bash
set -euo pipefail
umask 077
directory=/var/backups/axis
install -d -m 0700 "$directory"
scratch=$(mktemp -d "$directory/.work.XXXXXXXX")
archive=axis-$(date -u +'%Y%m%dT%H%M%SZ').tar.gz
trap 'rm -f -- "$scratch/axis.db" "$scratch/axis.db-shm" "$scratch/axis.db-wal" "$scratch/$archive"; rmdir -- "$scratch"' EXIT

sqlite3 -readonly /var/lib/axis/axis.db '.timeout 8000' ".backup '$scratch/axis.db'"
[[ $(sqlite3 -readonly "$scratch/axis.db" 'PRAGMA quick_check') == ok ]]
paths=(etc/axis etc/caddy/axis.Caddyfile etc/systemd/system/axis.service)
if [[ -d /etc/ntfy-axis ]]; then
    paths+=(etc/ntfy-axis)
fi
if [[ -d /etc/axis-voice ]]; then
    paths+=(etc/axis-voice etc/systemd/system/axis-voice@.service)
fi
if [[ -d /var/lib/caddy/.local/share/caddy/pki ]]; then
    paths+=(var/lib/caddy/.local/share/caddy/pki)
fi
if [[ -f /var/lib/axis-updates/version.json ]]; then
    paths+=(var/lib/axis-updates/version.json)
fi
tar -czf "$scratch/$archive" -C "$scratch" axis.db -C / "${paths[@]}"
mv -T "$scratch/$archive" "$directory/$archive"
find "$directory" -maxdepth 1 -type f -name 'axis-????????T??????Z.tar.gz' -mtime +13 -delete
printf 'Backup verified: %s\n' "$directory/$archive"
