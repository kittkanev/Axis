import asyncio
import hashlib
import logging
import os
import re
import secrets
import time
from contextlib import suppress

import httpx
from fastapi import HTTPException
from pydantic import BaseModel, ConfigDict, Field


CODE_TTL = 10 * 60
CHECK_INTERVAL = 60
VERIFIED_TTL = 5 * 60
CODE_LIMIT = 2048
LOGGER = logging.getLogger(__name__)


class BoostyCode(BaseModel):
    model_config = ConfigDict(extra="forbid")
    code: str = Field(pattern=r"^[a-fA-F0-9]{16}$")


class Unavailable(Exception):
    pass


def code_hash(code):
    return hashlib.sha256(code.lower().encode("ascii")).hexdigest()


class BoostyTelegram:
    def __init__(self, db, issue_limit):
        self.db = db
        self.issue_limit = issue_limit
        self.token = os.getenv("AXIS_BOOSTY_BOT_TOKEN", "")
        self.username = os.getenv("AXIS_BOOSTY_BOT_USERNAME", "").removeprefix("@")
        chat = os.getenv("AXIS_BOOSTY_CHAT_ID", "")
        self.chat = int(chat) if re.fullmatch(r"-[1-9][0-9]{4,16}", chat) else 0
        self.configured = bool(self.chat and re.fullmatch(r"[0-9]{5,16}:[A-Za-z0-9_-]{20,80}", self.token)
                               and re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{4,31}", self.username))
        self.ready = False
        self.bot_id = 0
        self.client = None
        self.worker = None
        self.requests = asyncio.Semaphore(3)
        with self.db() as conn:
            conn.executescript("""
                CREATE TABLE IF NOT EXISTS boosty_telegram_links (
                    account_id TEXT PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
                    chat_id INTEGER NOT NULL,
                    telegram_id INTEGER NOT NULL,
                    verified_until INTEGER NOT NULL,
                    check_after INTEGER NOT NULL,
                    generation INTEGER NOT NULL DEFAULT 0,
                    UNIQUE(chat_id, telegram_id)
                );
                CREATE TABLE IF NOT EXISTS boosty_telegram_codes (
                    hash TEXT PRIMARY KEY,
                    chat_id INTEGER NOT NULL,
                    telegram_id INTEGER NOT NULL,
                    expires INTEGER NOT NULL,
                    UNIQUE(chat_id, telegram_id)
                );
            """)

    def start(self):
        if self.configured:
            # Bot API credentials are part of its URL.
            logging.getLogger("httpx").setLevel(logging.WARNING)
            logging.getLogger("httpcore").setLevel(logging.WARNING)
            self.client = httpx.AsyncClient(timeout=httpx.Timeout(27, connect=5), trust_env=False)
            self.worker = asyncio.create_task(self.poll())
        elif self.token or self.username or self.chat:
            LOGGER.warning("Axis Boosty bridge configuration is incomplete")

    async def close(self):
        if self.worker is not None:
            self.worker.cancel()
            with suppress(asyncio.CancelledError):
                await self.worker
            await self.client.aclose()
        self.ready = False

    async def api(self, method, payload=None):
        try:
            response = await self.client.post(f"https://api.telegram.org/bot{self.token}/{method}", json=payload or {},
                                              timeout=httpx.Timeout(25 if method == "getUpdates" else 6, connect=5))
            response.raise_for_status()
            body = response.json()
            if body["ok"] is True:
                return body["result"]
        except (httpx.HTTPError, ValueError, KeyError, TypeError):
            pass
        raise Unavailable from None

    async def chat_member(self, telegram_id):
        if self.requests.locked():
            raise Unavailable
        async with self.requests:
            member = await self.api("getChatMember", {"chat_id": self.chat, "user_id": telegram_id})
        if not isinstance(member, dict) or member.get("user", {}).get("id") != telegram_id:
            raise Unavailable
        return member

    async def member(self, telegram_id):
        if not self.ready:
            raise Unavailable
        member = await self.chat_member(telegram_id)
        return member.get("status") == "member" and not member["user"].get("is_bot")

    async def check_configuration(self):
        bot = await self.api("getMe")
        self.bot_id = bot["id"]
        if bot.get("username", "").casefold() != self.username.casefold():
            raise Unavailable
        chat = await self.api("getChat", {"chat_id": self.chat})
        if chat.get("type") not in {"group", "supergroup"} or chat.get("username") or chat.get("active_usernames"):
            raise Unavailable
        if (await self.chat_member(self.bot_id)).get("status") != "administrator":
            raise Unavailable
        admins = await self.api("getChatAdministrators", {"chat_id": self.chat, "return_bots": True})
        if not any(admin["user"].get("is_bot") and admin["user"].get("username", "").lower() == "boosty_to_bot"
                   for admin in admins):
            raise Unavailable
        self.ready = True

    async def poll(self):
        offset = None
        retries = 0
        configuration_checked = 0.0
        while True:
            try:
                if not self.ready or time.monotonic() - configuration_checked >= 300:
                    await self.check_configuration()
                    configuration_checked = time.monotonic()
                query = {"timeout": 20, "limit": 25, "allowed_updates": ["message", "chat_member", "my_chat_member"]}
                if offset is not None:
                    query["offset"] = offset
                for update in await self.api("getUpdates", query):
                    await self.handle_update(update)
                    offset = update["update_id"] + 1
                retries = 0
            except (Unavailable, TypeError, KeyError, ValueError):
                self.ready = False
                if retries == 0:
                    LOGGER.warning("Axis Boosty bridge will retry Telegram")
                retries = min(retries + 1, 6)
                await asyncio.sleep(2 ** retries)

    async def handle_update(self, update):
        change = update.get("my_chat_member") or update.get("chat_member")
        if change and change["chat"]["id"] == self.chat:
            member = change["new_chat_member"]
            telegram_id = member["user"]["id"]
            if telegram_id == self.bot_id and member["status"] != "administrator":
                self.ready = False
            if member["status"] != "member":
                with self.db() as conn:
                    conn.execute("DELETE FROM boosty_telegram_codes WHERE chat_id=? AND telegram_id=?", (self.chat, telegram_id))
                    conn.execute("""UPDATE boosty_telegram_links SET verified_until=0, check_after=0, generation=generation+1
                                    WHERE chat_id=? AND telegram_id=?""", (self.chat, telegram_id))
            return

        message = update.get("message") or {}
        user = message.get("from") or {}
        chat = message.get("chat") or {}
        if chat.get("type") != "private" or user.get("is_bot") or user.get("id") != chat.get("id"):
            return
        if message.get("text") not in {"/start", "/code"}:
            return
        telegram_id = user["id"]
        try:
            self.issue_limit.check(telegram_id)
        except HTTPException:
            return
        try:
            if await self.member(telegram_id):
                text = self.issue_code(telegram_id)
            else:
                text = ("Подпишитесь на Axis Plus в Boosty, привяжите этот Telegram и вступите "
                        "в закрытую группу по приглашению Boosty. Затем отправьте /code.")
            await self.api("sendMessage", {"chat_id": telegram_id, "text": text})
        except Unavailable:
            LOGGER.warning("Axis Boosty bridge could not process a Telegram command")

    def issue_code(self, telegram_id):
        now = int(time.time())
        code = secrets.token_hex(8)
        with self.db() as conn:
            conn.execute("BEGIN IMMEDIATE")
            conn.execute("DELETE FROM boosty_telegram_codes WHERE expires<=? OR (chat_id=? AND telegram_id=?)",
                         (now, self.chat, telegram_id))
            if conn.execute("SELECT count(*) FROM boosty_telegram_codes").fetchone()[0] >= CODE_LIMIT:
                return "Слишком много запросов. Повторите /code позже."
            conn.execute("INSERT INTO boosty_telegram_codes VALUES (?,?,?,?)",
                         (code_hash(code), self.chat, telegram_id, now + CODE_TTL))
        return f"Код Axis Plus на 10 минут:\n{code}\n\nВведите его в Axis Cloud. Храните код в тайне."

    def link(self, conn, account):
        return conn.execute("""SELECT telegram_id, verified_until, check_after, generation
                               FROM boosty_telegram_links WHERE chat_id=? AND account_id=?""", (self.chat, account)).fetchone()

    async def claim(self, account, code):
        if not self.ready:
            raise HTTPException(503, "Boosty verification temporarily unavailable")
        digest = code_hash(code)
        with self.db() as conn:
            row = conn.execute("SELECT telegram_id FROM boosty_telegram_codes WHERE hash=? AND chat_id=? AND expires>?",
                               (digest, self.chat, int(time.time()))).fetchone()
        if row is None:
            raise HTTPException(404, "invalid or expired code")
        telegram_id = row[0]
        try:
            if not await self.member(telegram_id):
                raise HTTPException(403, "Boosty Plus membership is inactive")
        except Unavailable:
            raise HTTPException(503, "Boosty verification temporarily unavailable") from None
        now = int(time.time())
        with self.db() as conn:
            conn.execute("BEGIN IMMEDIATE")
            if not conn.execute("DELETE FROM boosty_telegram_codes WHERE hash=? AND chat_id=? AND expires>?",
                                (digest, self.chat, now)).rowcount:
                raise HTTPException(404, "invalid or expired code")
            matches = conn.execute("""SELECT account_id, telegram_id FROM boosty_telegram_links
                                      WHERE account_id=? OR (chat_id=? AND telegram_id=?)""",
                                   (account, self.chat, telegram_id)).fetchall()
            if any(owner != account or identity != telegram_id for owner, identity in matches):
                raise HTTPException(409, "Telegram or Axis account already linked")
            conn.execute("""INSERT INTO boosty_telegram_links
                            (account_id,chat_id,telegram_id,verified_until,check_after) VALUES (?,?,?,?,?)
                            ON CONFLICT(account_id) DO UPDATE SET chat_id=excluded.chat_id,
                              verified_until=excluded.verified_until, check_after=excluded.check_after,
                              generation=generation+1""", (account, self.chat, telegram_id,
                                                           now + VERIFIED_TTL, now + CHECK_INTERVAL))

    async def refresh(self, account):
        if not self.ready:
            return
        now = int(time.time())
        with self.db() as conn:
            conn.execute("BEGIN IMMEDIATE")
            link = self.link(conn, account)
            if link is None or link[2] > now:
                return
            telegram_id, _, _, generation = link
            conn.execute("""UPDATE boosty_telegram_links SET check_after=?, generation=generation+1
                            WHERE account_id=? AND chat_id=?""", (now + 15, account, self.chat))
        try:
            active = await self.member(telegram_id)
        except Unavailable:
            return
        now = int(time.time())
        with self.db() as conn:
            conn.execute("""UPDATE boosty_telegram_links SET verified_until=?, check_after=?
                            WHERE account_id=? AND chat_id=? AND generation=?""",
                         (now + VERIFIED_TTL if active else 0, now + CHECK_INTERVAL, account, self.chat, generation + 1))
