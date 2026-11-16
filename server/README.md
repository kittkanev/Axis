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

Boosty requires author identity verification. Confirm that Boosty supports paid access to your app under your country and author status, then create the 149 ₽ Axis Plus level. [Boosty author FAQ](https://boosty.to/boosty/posts/de1e6f32-3a80-4824-95bd-a264ff1b00aa)

1. Create a private Telegram group dedicated to Axis Plus. In Boosty, connect Telegram, add the official `@boosty_to_bot` as an administrator, assign the group only to the paid Plus level, and turn on subscriber removal when access ends. Let Boosty handle all invitations and avoid unrelated members and reusable invitation links. [Boosty's Telegram setup](https://boosty.to/boosty/posts/9b7e8eab-2a04-4fd9-a261-7e33f995f0ab)
2. Create a dedicated Axis bot with `@BotFather`, add it as an administrator in the same group, and keep its token private. Group membership checks require an administrator bot. The Axis bot uses outbound long polling and opens no public webhook. [Telegram Bot API](https://core.telegram.org/bots/api#getchatmember)
3. On the VPS, run `sudo python3 /opt/axis/configure-boosty.py`. Use the public [Axis Plus checkout](https://boosty.to/axisassistant/purchase/4178128) or another Boosty level or blog URL and paste the token into the masked prompt. Send the prompted setup command in the group from the owner's Telegram account. The helper checks the private group and bot administrators before saving `AXIS_BOOSTY_CHECKOUT_URL`, `AXIS_BOOSTY_BOT_TOKEN`, `AXIS_BOOSTY_BOT_USERNAME`, and `AXIS_BOOSTY_CHAT_ID` into the root-only environment file. Run `sudo systemctl restart axis.service`.
4. With Axis 0.6.1 or newer, sign into Axis Cloud. After buying Plus, link Telegram in Boosty and join from the official invitation. Tap **Get code in Telegram**, send `/start`, and enter the code in Axis Cloud. `POST /subscription/boosty/claim` links the authenticated Axis account to the verified Telegram ID. Codes last ten minutes and can be redeemed once.
5. Before advertising automatic activation, verify a paid account receives Plus, a nonmember cannot claim it, and Boosty removes access after the paid period. The purchase button stays hidden during testing. Once the flow is verified, change `AXIS_BOOSTY_CHECKOUT_ENABLED=0` to `1` in `/etc/axis/.env` and restart Axis. The VPS verifies membership on use with up to five minutes of cached confirmation and reacts to Telegram removal events. Boosty controls payment, invitations and membership removal, so removal timing also depends on Boosty. Outbound Telegram API access can be checked without a token: `curl -sS --max-time 8 -o /dev/null -w '%{http_code}\n' https://api.telegram.org/` should return a Telegram HTTP response.

For manual activation, verify the payment in the provider dashboard and identify the buyer's Axis account. Each reference can be applied once. A renewal extends the active period. A manual payment link can be set with `AXIS_PAYMENT_URL` for `boosty.to` or `pay.rollypay.io`.

```sh
sudo python3 server/ops/grant-plus.py USER_EMAIL --reference TRANSACTION_REFERENCE
```

Keep payment credentials in the provider dashboard. The app only opens the configured checkout URL. You can test account, quota and provider responses with `sudo python3 server/ops/smoke-plan.py`, which consumes one live DeepSeek request and grants a disposable test account a sample entitlement.
