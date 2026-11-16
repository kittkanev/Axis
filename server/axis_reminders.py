import asyncio
import json
import secrets
import threading
import time
from datetime import datetime, timezone

import httpx
from fastapi import Depends, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import AwareDatetime, BaseModel, ConfigDict, Field


NTFY = "http://127.0.0.1:2586"


class Reminder(BaseModel):
    model_config = ConfigDict(extra="forbid")
    dueAt: AwareDatetime
    text: str = Field(min_length=1, max_length=600)


class Reminders:
    def __init__(self, db, token, limiter):
        self.db = db
        self.token = token
        self.limiter = limiter
        self.stop = threading.Event()
        self.wake = threading.Event()
        self.thread = None
        self.streams = asyncio.Semaphore(8)
        with self.db() as conn:
            conn.executescript("""
                CREATE TABLE IF NOT EXISTS reminders (
                    id TEXT PRIMARY KEY,
                    account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                    due_at INTEGER NOT NULL,
                    next_attempt INTEGER NOT NULL,
                    text TEXT NOT NULL,
                    attempts INTEGER NOT NULL DEFAULT 0,
                    delivered INTEGER
                );
                CREATE INDEX IF NOT EXISTS reminders_due ON reminders(next_attempt) WHERE delivered IS NULL;
                CREATE INDEX IF NOT EXISTS reminders_account ON reminders(account_id, due_at);
            """)

    def require_token(self):
        if not self.token:
            raise HTTPException(503, "push is unavailable")
        return {"Authorization": f"Bearer {self.token}"}

    def start(self):
        if self.token:
            self.stop.clear()
            self.thread = threading.Thread(target=self.run, name="axis-reminders", daemon=True)
            self.thread.start()

    def close(self):
        self.stop.set()
        self.wake.set()
        if self.thread:
            self.thread.join(timeout=5)

    def run(self):
        with httpx.Client(timeout=4, trust_env=False) as client:
            while not self.stop.is_set():
                now = int(time.time())
                try:
                    self.deliver_due(client, now)
                except Exception:
                    # Do not print database values, message contents, or credentials.
                    print("Axis reminder worker could not process its queue", flush=True)
                self.wake.wait(timeout=2)
                self.wake.clear()

    def deliver_due(self, client, now):
        with self.db() as conn:
            batch = conn.execute("""SELECT id, account_id, text, attempts FROM reminders
                WHERE delivered IS NULL AND next_attempt <= ? ORDER BY next_attempt LIMIT 5""", (now,)).fetchall()
        for identifier, account, message, attempts in batch:
            if self.stop.is_set():
                return
            payload = json.dumps({"id": identifier, "text": message}, ensure_ascii=False).encode()
            try:
                response = client.post(f"{NTFY}/axis_{account}", content=payload, headers=self.require_token())
                response.raise_for_status()
                with self.db() as conn:
                    conn.execute("UPDATE reminders SET delivered=? WHERE id=?", (int(time.time()), identifier))
            except httpx.HTTPError:
                delay = min(30 * (2 ** min(attempts, 6)), 1800)
                with self.db() as conn:
                    conn.execute("UPDATE reminders SET attempts=attempts+1, next_attempt=? WHERE id=?",
                                 (int(time.time()) + delay, identifier))

    def register(self, app, account_for):
        @app.post("/reminders", status_code=201)
        def schedule(input: Reminder, account: str = Depends(account_for)):
            self.require_token()
            self.limiter.check(account)
            now = int(time.time())
            due = int(input.dueAt.timestamp())
            message = input.text.strip()
            if not message or not now + 3 <= due <= now + 366 * 86400:
                raise HTTPException(422, "reminder requires text and a future date within one year")
            identifier = secrets.token_hex(16)
            with self.db() as conn:
                conn.execute("BEGIN IMMEDIATE")
                if conn.execute("SELECT count(*) FROM reminders").fetchone()[0] >= 20000:
                    raise HTTPException(503, "reminder storage capacity reached")
                count = conn.execute("SELECT count(*) FROM reminders WHERE account_id=? AND delivered IS NULL",
                                     (account,)).fetchone()[0]
                if count >= 100:
                    raise HTTPException(409, "too many pending reminders")
                conn.execute("INSERT INTO reminders (id,account_id,due_at,next_attempt,text) VALUES (?,?,?,?,?)",
                             (identifier, account, due, due, message))
            self.wake.set()
            return {"id": identifier, "dueAt": datetime.fromtimestamp(due, timezone.utc).isoformat()}

        @app.get("/push/events")
        async def push(poll: bool = False, account: str = Depends(account_for)):
            headers = self.require_token()
            self.limiter.check(account)
            if self.streams.locked():
                raise HTTPException(429, "push connection limit reached", headers={"Retry-After": "30"})

            async def messages():
                if self.streams.locked():
                    yield '{"error":"push connection limit reached"}\n'
                    return
                timeout = httpx.Timeout(10, read=90)
                async with self.streams, httpx.AsyncClient(timeout=timeout, trust_env=False) as client:
                    try:
                        async with client.stream("GET", f"{NTFY}/axis_{account}/json", headers=headers,
                                                 params={"since": "all", **({"poll": "1"} if poll else {})}) as stream:
                            stream.raise_for_status()
                            connection_deadline = time.monotonic() + 3600
                            async for line in stream.aiter_lines():
                                event = json.loads(line)
                                if event.get("event") == "message":
                                    yield event["message"] + "\n"
                                elif not poll:
                                    yield "{}\n"
                                if time.monotonic() > connection_deadline:
                                    break
                    except (httpx.HTTPError, ValueError, KeyError):
                        yield '{"error":"push temporarily unavailable"}\n'

            return StreamingResponse(messages(), media_type="application/x-ndjson", headers={"Cache-Control": "no-store"})
