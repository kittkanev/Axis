"""Configure the official Boosty Telegram group bridge on the Axis VPS."""

import getpass
import json
import os
import re
import tempfile
from pathlib import Path
from urllib.error import URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen


def configure():
    env = Path("/etc/axis/.env")
    stat = env.stat()
    if stat.st_uid != 0 or stat.st_mode & 0o777 != 0o600:
        raise SystemExit("Expected /etc/axis/.env owned by root with mode 600.")
    existing = env.read_text(encoding="utf-8").splitlines()
    staged_url = next((line.partition("=")[2] for line in existing
                       if line.startswith("AXIS_BOOSTY_CHECKOUT_URL=")), "")
    raw_url = input(f"Public Boosty checkout or blog URL [{staged_url or 'required'}]: ").strip() or staged_url
    url = urlsplit(raw_url)
    path = url.path.rstrip("/")
    path = re.sub(r"/subscription-levels/([0-9]+)$", r"/purchase/\1", path)
    if url.scheme != "https" or url.netloc != "boosty.to" or not re.fullmatch(r"/[a-zA-Z0-9_.-]+(?:/purchase/[0-9]+)?", path):
        raise SystemExit("Enter an HTTPS Boosty blog or purchase link.")
    token = getpass.getpass("Token for the dedicated Axis BotFather bot: ").strip()
    if not re.fullmatch(r"[0-9]{5,16}:[A-Za-z0-9_-]{20,80}", token):
        raise SystemExit("Invalid Telegram bot token format.")

    def telegram(method, payload=None):
        try:
            request = Request(f"https://api.telegram.org/bot{token}/{method}",
                              json.dumps(payload or {}).encode(), {"Content-Type": "application/json"})
            with urlopen(request, timeout=8) as response:
                return json.load(response)["result"]
        except (URLError, TimeoutError, ValueError, KeyError):
            raise SystemExit(f"Telegram {method} did not return a usable result. Check connectivity and bot setup.") from None

    bot = telegram("getMe")
    username = bot["username"]
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{4,31}", username):
        raise SystemExit("Invalid Telegram bot username.")
    command = f"/axis_setup@{username}"
    input(f"Send {command} in the private Axis Plus group as its owner, then press Enter here. ")
    updates = telegram("getUpdates", {"allowed_updates": ["message"], "timeout": 0})
    chats = {}
    for update in updates:
        message = update.get("message", {})
        chat = message.get("chat", {})
        if chat.get("type") not in {"group", "supergroup"} or message.get("text") != command:
            continue
        sender = message.get("from") or {}
        if sender.get("is_bot") or not sender.get("id"):
            continue
        chats[chat["id"]] = sender["id"]
    if len(chats) != 1:
        raise SystemExit("Expected one private Axis Plus group setup command. Use a new dedicated bot.")
    chat_id, owner = chats.popitem()
    room = telegram("getChat", {"chat_id": chat_id})
    if room.get("username") or room.get("active_usernames"):
        raise SystemExit("The Axis Plus group must be private.")
    if telegram("getChatMember", {"chat_id": chat_id, "user_id": owner}).get("status") != "creator":
        raise SystemExit("Send the setup command as the group owner with anonymous admin mode off.")
    admins = telegram("getChatAdministrators", {"chat_id": chat_id, "return_bots": True})
    bots = {admin["user"].get("username", "").casefold() for admin in admins if admin["user"].get("is_bot")}
    if not {"boosty_to_bot", username.casefold()} <= bots:
        raise SystemExit("Add both @boosty_to_bot and the Axis bot as group administrators.")
    print(f"Verified private group {chat_id} and @{username}.")
    settings = {
        "AXIS_BOOSTY_CHECKOUT_URL": "https://boosty.to" + path,
        "AXIS_BOOSTY_CHECKOUT_ENABLED": "0",
        "AXIS_BOOSTY_BOT_TOKEN": token,
        "AXIS_BOOSTY_BOT_USERNAME": username,
        "AXIS_BOOSTY_CHAT_ID": str(chat_id),
    }
    lines = [line for line in existing if line.partition("=")[0].strip() not in settings]
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=env.parent, delete=False) as output:
        staged = Path(output.name)
        os.fchmod(output.fileno(), 0o600)
        output.write("\n".join(lines + [f"{key}={value}" for key, value in settings.items()]) + "\n")
    try:
        staged.replace(env)
    finally:
        staged.unlink(missing_ok=True)
    print("Configuration saved. Run systemctl restart axis.service. Checkout stays hidden until tested.")


if __name__ == "__main__":
    if os.geteuid() != 0:
        raise SystemExit("Run this command as root on the VPS.")
    configure()
