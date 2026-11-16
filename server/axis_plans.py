import asyncio
import time
from datetime import datetime, timezone
from urllib.parse import urlsplit

import httpx
from anyio import CancelScope
from fastapi import Depends, HTTPException
from fastapi.responses import Response, StreamingResponse
from pydantic import BaseModel, ConfigDict, Field
from starlette.background import BackgroundTask

from axis_boosty import BoostyCode


MODEL = "deepseek-v4-1-flash-1017"
UPSTREAM = "https://coarena.ai/api/v1/chat/completions"
FREE_DAILY = 10
PLUS_DAILY = 60
PRICE_RUB = 149


class Turn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    role: str = Field(pattern=r"^(system|user|assistant)$")
    content: str = Field(max_length=48000)


class Completion(BaseModel):
    model_config = ConfigDict(extra="forbid")
    model: str
    stream: bool = True
    messages: list[Turn] = Field(min_length=1, max_length=24)
    tools: list[dict] | None = Field(default=None, max_length=15)


def initialize(conn):
    conn.executescript("""
        CREATE TABLE IF NOT EXISTS entitlements (
            account_id TEXT PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
            expires INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS plan_payments (
            reference TEXT PRIMARY KEY,
            account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            granted INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS plan_usage (
            day TEXT NOT NULL,
            account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            count INTEGER NOT NULL,
            PRIMARY KEY(day, account_id)
        );
    """)


class Plans:
    def __init__(self, db, limiter, key, checkout, daily_cap, boosty, boosty_limiter, boosty_checkout=None):
        self.db = db
        self.limiter = limiter
        self.key = key
        self.boosty = boosty
        self.boosty_limiter = boosty_limiter
        self.slots = asyncio.Semaphore(2)
        self.free_until = 0.0
        # These hosts issue hosted checkout links for the account owner.
        uri = urlsplit(checkout or "")
        self.checkout = checkout if uri.scheme == "https" and uri.hostname in {"boosty.to", "pay.rollypay.io"} and not uri.username else None
        uri = urlsplit(boosty_checkout or "")
        self.boosty_checkout = boosty_checkout if uri.scheme == "https" and uri.netloc == "boosty.to" else None
        self.daily_cap = max(1, min(daily_cap, 1000))
        with db() as conn:
            initialize(conn)

    def state(self, conn, account, day, now):
        entry = conn.execute("SELECT expires FROM entitlements WHERE account_id=?", (account,)).fetchone()
        expires = entry[0] if entry and entry[0] > now else None
        used = conn.execute("SELECT count FROM plan_usage WHERE day=? AND account_id=?", (day, account)).fetchone()
        link = self.boosty.link(conn, account) if self.boosty.configured else None
        active = link is not None and link[1] > now
        source = "boosty" if active else "manual" if expires else None
        return source, expires if source == "manual" else None, used[0] if used else 0, link is not None

    def subscription_state(self, account):
        day = datetime.now(timezone.utc).date().isoformat()
        with self.db() as conn:
            source, expiry, used, linked = self.state(conn, account, day, int(time.time()))
        limit = PLUS_DAILY if source else FREE_DAILY
        bot_url = f"https://t.me/{self.boosty.username}" if self.boosty.ready else None
        checkout = (self.boosty_checkout if self.boosty.ready else None) if self.boosty.configured or self.boosty_checkout else self.checkout
        return {"tier": "plus" if source else "free", "expiresAt": expiry, "model": MODEL, "enabled": bool(self.key),
                "remaining": max(0, limit-used), "limit": limit, "freeLimit": FREE_DAILY, "plusLimit": PLUS_DAILY,
                "priceRub": PRICE_RUB, "checkoutUrl": checkout, "periodDays": 30,
                "source": source, "boostyLinked": linked, "boostyBotUrl": bot_url}

    def debit(self, account):
        now = datetime.now(timezone.utc)
        day = now.date().isoformat()
        with self.db() as conn:
            conn.execute("BEGIN IMMEDIATE")
            source, _, used, _ = self.state(conn, account, day, int(now.timestamp()))
            if used >= (PLUS_DAILY if source else FREE_DAILY):
                raise HTTPException(429, "daily plan limit reached", headers={"Retry-After": "3600"})
            if conn.execute("SELECT COALESCE(sum(count),0) FROM plan_usage WHERE day=?", (day,)).fetchone()[0] >= self.daily_cap:
                raise HTTPException(429, "shared model capacity reached", headers={"Retry-After": "3600"})
            conn.execute("DELETE FROM plan_usage WHERE day < date(?, '-30 days')", (day,))
            conn.execute("INSERT INTO plan_usage VALUES (?,?,1) ON CONFLICT(day,account_id) DO UPDATE SET count=count+1", (day, account))
        return day

    def refund(self, account, day):
        with self.db() as conn:
            conn.execute("UPDATE plan_usage SET count=max(0,count-1) WHERE day=? AND account_id=?", (day, account))

    def register(self, app, account_for):
        @app.get("/subscription")
        async def subscription(account: str = Depends(account_for)):
            await self.boosty.refresh(account)
            return self.subscription_state(account)

        @app.post("/subscription/boosty/claim")
        async def claim_boosty(input: BoostyCode, account: str = Depends(account_for)):
            self.boosty_limiter.check(account)
            await self.boosty.claim(account, input.code)
            return self.subscription_state(account)

        @app.get("/provider/v1/models")
        def models(_account: str = Depends(account_for)):
            return {"object": "list", "data": [{"id": MODEL, "object": "model"}] if self.key else []}

        @app.post("/provider/v1/chat/completions")
        async def complete(input: Completion, account: str = Depends(account_for)):
            if not self.key:
                raise HTTPException(503, "bundled model unavailable")
            if input.model != MODEL or sum(len(turn.content) for turn in input.messages) > 50000:
                raise HTTPException(422, "unsupported model or context too large")
            self.limiter.check(account)
            await self.boosty.refresh(account)
            if self.slots.locked():
                raise HTTPException(429, "bundled model is busy", headers={"Retry-After": "2"})
            await self.slots.acquire()
            client = httpx.AsyncClient(timeout=httpx.Timeout(55, connect=8), follow_redirects=False, trust_env=False)
            released = False

            async def release():
                nonlocal released
                if released:
                    return
                released = True
                try:
                    with CancelScope(shield=True):
                        await client.aclose()
                finally:
                    self.slots.release()

            try:
                if time.monotonic() >= self.free_until:
                    catalog = await client.get("https://coarena.ai/api/v1/models", timeout=8)
                    catalog.raise_for_status()
                    candidate = next((item for item in catalog.json().get("data", []) if item.get("id") == MODEL), {})
                    pricing = candidate.get("pricing", {})
                    if any(str(pricing.get(item)) not in {"0", "0.0"} for item in ("prompt", "completion")):
                        raise HTTPException(503, "free model is currently unavailable")
                    self.free_until = time.monotonic() + 300
                day = self.debit(account)
            except (httpx.HTTPError, ValueError, AttributeError):
                await release()
                raise HTTPException(503, "free model catalog is temporarily unavailable") from None
            except BaseException:
                await release()
                raise
            try:
                body = input.model_dump(exclude_none=True)
                body.update(max_tokens=1000, store=False)
                request = client.build_request("POST", UPSTREAM, json=body, headers={"Authorization": f"Bearer {self.key}"})
                upstream = await client.send(request, stream=True)
            except (httpx.HTTPError, asyncio.CancelledError) as exc:
                await release()
                self.refund(account, day)
                if isinstance(exc, asyncio.CancelledError):
                    raise
                raise HTTPException(503, "bundled provider temporarily unavailable") from None
            if upstream.status_code != 200:
                status = upstream.status_code
                await release()
                self.refund(account, day)
                if status in {402, 429}:
                    raise HTTPException(429, "provider free capacity exhausted", headers={"Retry-After": "3600"})
                if status in {400, 422}:
                    raise HTTPException(422, "provider rejected the request")
                raise HTTPException(503, "bundled provider temporarily unavailable")

            async def output():
                size = 0
                try:
                    async for chunk in upstream.aiter_bytes():
                        size += len(chunk)
                        if size > 2 * 1024 * 1024:
                            return
                        yield chunk
                finally:
                    await release()

            if input.stream:
                return StreamingResponse(output(), media_type="text/event-stream",
                                         headers={"Cache-Control": "no-store"}, background=BackgroundTask(release))
            try:
                parts = [part async for part in output()]
                return Response(b"".join(parts), media_type="application/json", headers={"Cache-Control": "no-store"})
            finally:
                await release()
