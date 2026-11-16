# Axis VPS

The local deployment uses Ubuntu 22.04, the `axis` system user, and Caddy. UFW permits SSH on port `22` inside the guest. The Caddy listener uses only `127.0.0.1:8443` and presents a certificate issued by its private CA.

## Base setup

Copy `server/ops/` to the VPS and run `bash ops/phase0.sh` as root inside the active SSH session. The script configures a persistent 2 GiB swap file, sets `vm.swappiness=10`, updates Ubuntu packages, and starts Caddy and Fail2ban with systemd memory limits. It keeps the existing Ubuntu security update timers enabled.

```sh
systemctl status caddy fail2ban ssh unattended-upgrades
fail2ban-client status sshd
ufw status verbose
swapon --show
free -m
df -h /
```

## Check HTTPS from a workstation

Set `SSH_HOST`, `SSH_PORT`, and `SSH_USER` to the VPS's public address, forwarded SSH port and SSH user. Copy the public CA certificate to the workstation and start an SSH tunnel.

```sh
scp -P "$SSH_PORT" "$SSH_USER@$SSH_HOST:/var/lib/caddy/.local/share/caddy/pki/authorities/local/root.crt" axis-local-ca.crt
ssh -N -o ExitOnForwardFailure=yes -L 127.0.0.1:8443:127.0.0.1:8443 -p "$SSH_PORT" "$SSH_USER@$SSH_HOST"
```

In another terminal, run the HTTPS check.

```sh
curl --cacert axis-local-ca.crt https://localhost:8443/proxy-healthz
```

## Restart and update the proxy

```sh
sudo -u caddy caddy validate --config /etc/caddy/axis.Caddyfile --adapter caddyfile
sudo systemctl reload caddy
sudo apt-get update
sudo apt-get install --only-upgrade caddy fail2ban
```

Keep the Caddy data directory when moving a local deployment if its clients already trust its CA. The CA private key stays on the server.

## Backend

Copy `server/` and run `sudo bash server/ops/deploy.sh` after the base setup. The service runs as `axis` with one Uvicorn worker behind Caddy. It uses SQLite at `/var/lib/axis/axis.db` and a root-owned `/etc/axis/.env` with mode `600`. The deploy script generates the JWT secret on the first install. Rotating that secret invalidates existing sessions.

```sh
systemctl status axis.service axis-backup.timer
sudo systemctl restart axis.service
sudo systemctl start axis-backup.service
journalctl -u axis-backup.service -n 10
```

Deploy server updates with the same script. Backups use SQLite's online backup command and run an integrity check before archiving. Archives rotate after 14 days under `/var/backups/axis/` with mode `600`. They contain the database, server credentials, and local TLS CA keys, so handle them as secret material. For a restore, inspect the archive and verify `axis.db` with `sqlite3 -readonly axis.db 'PRAGMA quick_check'` in a private staging directory. Stop `axis` before replacing its database and restore the `axis:axis` owner and mode `600`. Restart Caddy if restoring its configuration or CA. Keep a copy of the current database before restoring.

Publish a previously signed and verified APK using `sudo python3 server/ops/publish-apk.py APK VERSION_CODE VERSION_NAME PACKAGE_NAME TAG CHANGELOG_FILE`. This sets the current channel atomically, serves the actual file from disk, and reports its SHA-256. The backend caches the matching GitHub release notes for 30 minutes in SQLite. Test the deployed endpoints with `sudo python3 server/ops/smoke-api.py`. The smoke creates two disposable accounts and writes test bytes to one account.

## API examples

These examples assume the SSH tunnel is connected and `axis-local-ca.crt` is in the current directory. Create `credentials.json` with mode `600` and the fields `email` and `password`. Use a password of 12 to 128 characters. `token` is the `access_token` from the login response.

```sh
base=https://localhost:8443
curl --cacert axis-local-ca.crt "$base/healthz"
curl --cacert axis-local-ca.crt -H 'Content-Type: application/json' --data-binary @credentials.json "$base/auth/register"
curl --cacert axis-local-ca.crt -H 'Content-Type: application/json' --data-binary @credentials.json "$base/auth/login"
curl --cacert axis-local-ca.crt -H "Authorization: Bearer $token" "$base/sync/blob"
curl --cacert axis-local-ca.crt -X PUT -H "Authorization: Bearer $token" -H 'Content-Type: application/json' --data-binary @encrypted-update.json "$base/sync/blob"
curl --cacert axis-local-ca.crt "$base/version.json"
curl --cacert axis-local-ca.crt -O "$base/apk/Axis-0.5.0-preview.apk"
```

`encrypted-update.json` has `revision` and `blob` fields. `blob` is the base64-encoded output of client-side encryption. A workspace begins at revision `0`. Each accepted write replaces the previous blob and increments the revision. On HTTP `409`, fetch the latest revision before deciding how to reconcile. The quota is one blob of up to 1 MiB per account.

## Reminders

Run `sudo bash server/ops/phase2.sh` after the backend is deployed. The script installs the pinned amd64 ntfy binary and a local-only service. Caddy exposes Axis's authenticated push endpoint. Each account receives only its own topic. Axis holds the ntfy service token in root-owned `.env` files with mode `600`.

```sh
systemctl status ntfy-axis.service axis.service
sudo systemctl restart ntfy-axis.service
curl --cacert axis-local-ca.crt -H "Authorization: Bearer $token" -H 'Content-Type: application/json' --data-binary @reminder.json "$base/reminders"
curl -N --cacert axis-local-ca.crt -H "Authorization: Bearer $token" "$base/push/events"
```

The request body has `text` and `dueAt`. Supply a future date in ISO 8601 with a timezone. The response contains a stable reminder `id`. The push stream returns newline-delimited JSON with `id` and `text`. Use `?poll=true` for one read of cached events. The cache retains events for 12 hours. Deduplicate by reminder `id` when reconnecting. `sudo python3 server/ops/smoke-push.py` exercises scheduling, delivery, and account isolation.

## Web tools

Each tool requires a bearer token and takes a JSON body. Results use `{"text":"…"}`. Requests are rate limited per account and successful results are cached in SQLite. Search uses DuckDuckGo Lite unless `AXIS_BRAVE_KEY` is set in `/etc/axis/.env`. Restart `axis.service` after adding or changing that key.

| Endpoint | Request body |
| --- | --- |
| `POST /tools/search` | `{"query":"Jetpack Compose"}` |
| `POST /tools/read_url` | `{"url":"https://example.org/"}` |
| `POST /tools/weather` | `{"city":"Moscow"}` |
| `POST /tools/currency` | `{"base":"USD","target":"EUR"}` |
| `POST /tools/wiki` | `{"title":"Москва","language":"ru"}` |

```sh
curl --cacert axis-local-ca.crt -H "Authorization: Bearer $token" -H 'Content-Type: application/json' --data '{"city":"Moscow"}' "$base/tools/weather"
sudo python3 server/ops/smoke-tools.py
```

Page reads follow at most three redirects and cap the downloaded body at 1 MiB. Each destination is resolved and checked for public addresses before opening the connection. HTML extraction uses Trafilatura. Weather, currency, and encyclopedia data come from Open-Meteo, Frankfurter, and Wikipedia.

## Voice

`sudo bash server/ops/phase4.sh piper` installs the Russian Irina model and enables the Piper unit with a 300 MiB memory ceiling, one running synthesis, and one waiting request. Set up the voice unit and then rerun `deploy.sh` to enable the API proxy. `phase4.sh vosk` is available when the machine has enough memory for a second model. On the 1 GiB deployment, Piper is enabled and Vosk is deferred.

```sh
systemctl status axis-voice@piper.service
sudo systemctl restart axis-voice@piper.service
curl --cacert axis-local-ca.crt -H "Authorization: Bearer $token" -H 'Content-Type: application/json' --data '{"text":"Привет, это Axis"}' "$base/voice/tts" -o axis-voice.wav
curl --cacert axis-local-ca.crt -H "Authorization: Bearer $token" -H 'Content-Type: audio/wav' --data-binary @mono-16khz.wav "$base/voice/stt"
sudo python3 server/ops/smoke-voice.py
```

The STT endpoint expects 16-bit mono PCM WAV at 16 kHz for up to 20 seconds. Use `smoke-voice.py --stt` when both engines are active. Model files come from the [Piper voice catalog](https://huggingface.co/rhasspy/piper-voices/tree/main/ru/ru_RU/irina/medium) and [Vosk model catalog](https://alphacephei.com/vosk/models).

## DeepSeek and plans

Set `COARENA_API_KEY` on the VPS in `/etc/axis/.env` with owner `root` and mode `600`. Restart `axis.service`. Axis exposes a single DeepSeek model through `/provider/v1`. The backend checks the catalog price before proxying and stops serving the bundled model if it ceases to be free. Coarena retains its own inference traffic. Workspace backups use the separate encrypted sync API. [Coarena API and retention information](https://coarena.ai/developers)

| Plan | Quota | Period |
| --- | --- | --- |
| Free | 10 requests per day | Available after registration |
| Plus | 60 requests per day | 149 ₽ per 30 days after activation |

Quotas reset at 00:00 UTC. The shared Coarena capacity also applies. Set `AXIS_PROVIDER_DAILY_CAP` to control how much of it Axis can use. The default is 120 per day. A paid entitlement changes the Axis quota. The upstream remains the published free model.

```sh
curl --cacert axis-local-ca.crt -H "Authorization: Bearer $token" "$base/subscription"
curl --cacert axis-local-ca.crt -H "Authorization: Bearer $token" "$base/provider/v1/models"
curl -N --cacert axis-local-ca.crt -H "Authorization: Bearer $token" -H 'Content-Type: application/json' --data '{"model":"deepseek-v4-1-flash-1017","stream":true,"messages":[{"role":"user","content":"Say hello"}]}' "$base/provider/v1/chat/completions"
```

### Hosted checkout and activation

After your payment platform approves the project and its membership, add the hosted checkout URL as `AXIS_PAYMENT_URL` in `/etc/axis/.env` and restart `axis.service`. The supported checkout hosts are `boosty.to` and `pay.rollypay.io`. Set the membership price to match the price shown in Axis. Boosty requires author identity verification and describes how authors handle taxes on subscription revenue. Ask the platform to confirm that the membership can include external app access and covers your country and status. [Boosty author FAQ](https://boosty.to/boosty/posts/de1e6f32-3a80-4824-95bd-a264ff1b00aa)

After verifying a payment in the provider dashboard and identifying the buyer's Axis account, activate Plus via SSH. Each reference can be applied once. A renewal extends the active period. This flow uses a hosted payment link and manual reconciliation.

```sh
sudo python3 server/ops/grant-plus.py USER_EMAIL --reference TRANSACTION_REFERENCE
```

Keep payment credentials in the provider dashboard. The app only opens the configured checkout URL. You can test account, quota and provider responses with `sudo python3 server/ops/smoke-plan.py`, which consumes one live DeepSeek request and grants a disposable test account a sample entitlement.
