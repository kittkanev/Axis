import base64
import binascii
import json
import os
import re
import secrets
import sqlite3
import threading
import time
from contextlib import asynccontextmanager, contextmanager
from dataclasses import dataclass
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request as GitHubRequest, urlopen

import jwt
from argon2 import PasswordHasher
from argon2.exceptions import VerificationError
from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel, ConfigDict, Field

from axis_reminders import Reminders
from axis_plans import Plans
from axis_voice_proxy import MAX_WAV, VoiceProxy
from axis_webtools import WebTools


MAX_BLOB = 1024 * 1024
MAX_ENCODED = ((MAX_BLOB + 2) // 3) * 4
MAX_BODY = MAX_ENCODED + 256
TOKEN_TTL = 24 * 3600
SESSION_TTL = 30 * TOKEN_TTL
ISSUER = "axis-backend"
EMAIL = re.compile(r"[^\s@]+@[^\s@]+\.[^\s@]+", re.ASCII)
APK_NAME = re.compile(r"Axis-[A-Za-z0-9._-]+\.apk")
RELEASE_TAG = re.compile(r"v[A-Za-z0-9._-]+")


@dataclass(frozen=True)
class Config:
    data_dir: Path
    updates_dir: Path
    jwt_key: bytes
    max_accounts: int = 1000

    @classmethod
    def from_env(cls):
        key = bytes.fromhex(os.environ["AXIS_JWT_KEY"])
        if len(key) != 32:
            raise ValueError("AXIS_JWT_KEY must contain 32 random bytes in hex")
        return cls(Path(os.environ.get("AXIS_DATA_DIR", "/var/lib/axis")),
                   Path(os.environ.get("AXIS_UPDATES_DIR", "/var/lib/axis-updates")), key)


class Credentials(BaseModel):
    model_config = ConfigDict(extra="forbid")
    email: str = Field(min_length=3, max_length=254)
    password: str = Field(min_length=12, max_length=128)


class BlobUpdate(BaseModel):
    model_config = ConfigDict(extra="forbid")
    revision: int = Field(strict=True, ge=0, lt=2**63 - 1)
    blob: str = Field(min_length=1, max_length=MAX_ENCODED)


class UpdateManifest(BaseModel):
    versionCode: int = Field(gt=0)
    versionName: str = Field(min_length=1, max_length=64)
    packageName: str = Field(pattern=r"^io\.github\.kittkanev\.axis(?:\.preview[0-9]*)?$")
    file: str = Field(max_length=128)
    sha256: str = Field(pattern=r"^[a-f0-9]{64}$")
    changelog: str = Field(max_length=8192)
    tag: str = Field(max_length=64)


class BoundedRequestBody:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or scope["method"] not in {"POST", "PUT"}:
            return await self.app(scope, receive, send)
        limit = {"/sync/blob": MAX_BODY, "/voice/stt": MAX_WAV,
                 "/provider/v1/chat/completions": 160 * 1024}.get(scope["path"], 4096)
        length = dict(scope["headers"]).get(b"content-length", b"0")
        if not length.isdigit() or len(length) > 8 or int(length) > limit:
            response = JSONResponse({"detail": "request too large"}, status_code=413)
            return await response(scope, receive, send)
        length_read = 0

        async def bounded_receive():
            nonlocal length_read
            message = await receive()
            length_read += len(message.get("body", b""))
            if length_read > limit:
                raise HTTPException(413, "request too large")
            return message

        return await self.app(scope, bounded_receive, send)


class RateLimiter:
    def __init__(self, limit, window=60, capacity=2048):
        self.limit = limit
        self.window = window
        self.capacity = capacity
        self.slots = {}
        self.lock = threading.Lock()

    def check(self, key):
        window_id = int(time.monotonic()) // self.window
        with self.lock:
            if len(self.slots) >= self.capacity:
                self.slots = {name: slot for name, slot in self.slots.items() if slot[0] == window_id}
            previous, count = self.slots.get(key, (-1, 0))
            if previous != window_id:
                count = 0
            if count >= self.limit or (key not in self.slots and len(self.slots) >= self.capacity):
                raise HTTPException(429, "too many requests", headers={"Retry-After": str(self.window)})
            self.slots[key] = (window_id, count + 1)


def create_app(config=None):
    config = config or Config.from_env()

    @asynccontextmanager
    async def lifespan(_app):
        reminders.start()
        try:
            yield
        finally:
            reminders.close()

    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)
    app.add_middleware(BoundedRequestBody)
    passwords = PasswordHasher(time_cost=2, memory_cost=32768, parallelism=1)
    dummy_hash = passwords.hash(secrets.token_hex(32))
    password_slot = threading.BoundedSemaphore(1)
    auth_global = RateLimiter(30, capacity=1)
    auth_by_ip = RateLimiter(8)
    sync_by_account = RateLimiter(24)
    release_lock = threading.Lock()

    @contextmanager
    def db():
        connection = sqlite3.connect(config.data_dir / "axis.db", timeout=5)
        try:
            connection.execute("PRAGMA foreign_keys=ON")
            with connection:
                yield connection
        finally:
            connection.close()

    with db() as conn:
        conn.execute("PRAGMA journal_mode=WAL")
        conn.executescript("""
            CREATE TABLE IF NOT EXISTS accounts (
                id TEXT PRIMARY KEY,
                email TEXT NOT NULL UNIQUE,
                password_hash TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS workspaces (
                account_id TEXT PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
                revision INTEGER NOT NULL CHECK (revision > 0),
                blob BLOB NOT NULL
            );
            CREATE TABLE IF NOT EXISTS release_cache (
                tag TEXT PRIMARY KEY,
                checked INTEGER NOT NULL,
                etag TEXT NOT NULL,
                changelog TEXT NOT NULL
            );
        """)

    @app.exception_handler(RequestValidationError)
    async def invalid_request(_request, _error):
        return JSONResponse({"detail": "invalid request body"}, status_code=422)

    def email_for(creds, request):
        auth_by_ip.check(request.client.host if request.client else "unknown")
        auth_global.check("auth")
        email = creds.email.strip().lower()
        if not EMAIL.fullmatch(email):
            raise HTTPException(422, "invalid email")
        return email

    @contextmanager
    def password_worker():
        if not password_slot.acquire(blocking=False):
            raise HTTPException(429, "authentication is busy", headers={"Retry-After": "1"})
        try:
            yield
        finally:
            password_slot.release()

    def session(account, authenticated_at=None):
        now = int(time.time())
        started = authenticated_at if authenticated_at is not None else now
        expiry = min(now + TOKEN_TTL, started + SESSION_TTL)
        if expiry <= now:
            raise HTTPException(401, "sign in again")
        token = jwt.encode({"sub": account, "iss": ISSUER, "iat": now, "exp": expiry, "auth_time": started},
                           config.jwt_key, algorithm="HS256")
        return JSONResponse({"access_token": token, "token_type": "bearer", "expires_in": expiry-now},
                            headers={"Cache-Control": "no-store"})

    def claims_for(authorization: str = Header(default="")):
        scheme, _, token = authorization.partition(" ")
        try:
            if scheme.lower() != "bearer" or len(token) > 2048:
                raise jwt.InvalidTokenError()
            return jwt.decode(token, config.jwt_key, algorithms=["HS256"], issuer=ISSUER,
                                options={"require": ["sub", "iss", "iat", "exp"]})
        except jwt.InvalidTokenError:
            raise HTTPException(401, "invalid session", headers={"WWW-Authenticate": "Bearer"}) from None

    def account_for(claims: dict = Depends(claims_for)):
        return claims["sub"]

    reminders = Reminders(db, os.environ.get("AXIS_NTFY_TOKEN"), RateLimiter(20))
    reminders.register(app, account_for)
    WebTools(db, RateLimiter(15), os.environ.get("AXIS_BRAVE_KEY")).register(app, account_for)
    VoiceProxy(RateLimiter(10)).register(app, account_for)
    Plans(db, RateLimiter(8, window=60), os.environ.get("COARENA_API_KEY"),
          os.environ.get("AXIS_PAYMENT_URL"), int(os.environ.get("AXIS_PROVIDER_DAILY_CAP", "120"))).register(app, account_for)

    @app.get("/healthz")
    def health():
        with db() as conn:
            conn.execute("SELECT 1").fetchone()
        return {"status": "ok"}

    @app.post("/auth/renew")
    def renew(claims: dict = Depends(claims_for)):
        return session(claims["sub"], claims.get("auth_time", claims["iat"]))

    @app.post("/auth/register")
    def register(creds: Credentials, request: Request):
        email = email_for(creds, request)
        with password_worker():
            password_hash = passwords.hash(creds.password)
        with db() as conn:
            conn.execute("BEGIN IMMEDIATE")
            if conn.execute("SELECT count(*) FROM accounts").fetchone()[0] >= config.max_accounts:
                raise HTTPException(503, "registration capacity reached")
            account = secrets.token_hex(16)
            inserted = conn.execute("INSERT INTO accounts VALUES (?, ?, ?) ON CONFLICT(email) DO NOTHING",
                                    (account, email, password_hash))
            if inserted.rowcount != 1:
                raise HTTPException(409, "email already registered")
        reply = session(account)
        reply.status_code = 201
        return reply

    @app.post("/auth/login")
    def login(creds: Credentials, request: Request):
        email = email_for(creds, request)
        with db() as conn:
            user = conn.execute("SELECT id, password_hash FROM accounts WHERE email = ?", (email,)).fetchone()
        with password_worker():
            try:
                passwords.verify(user[1] if user else dummy_hash, creds.password)
            except VerificationError:
                raise HTTPException(401, "invalid credentials") from None
        if not user:
            raise HTTPException(401, "invalid credentials")
        return session(user[0])

    @app.get("/sync/blob")
    def get_blob(account: str = Depends(account_for)):
        sync_by_account.check(account)
        with db() as conn:
            row = conn.execute("SELECT revision, blob FROM workspaces WHERE account_id = ?", (account,)).fetchone()
        data = {"revision": row[0], "blob": base64.b64encode(row[1]).decode("ascii")} if row else {"revision": 0, "blob": None}
        return JSONResponse(data, headers={"Cache-Control": "no-store"})

    @app.put("/sync/blob")
    def put_blob(update: BlobUpdate, account: str = Depends(account_for)):
        sync_by_account.check(account)
        try:
            payload = base64.b64decode(update.blob, validate=True)
        except (ValueError, binascii.Error):
            raise HTTPException(422, "blob must be base64") from None
        if not payload or len(payload) > MAX_BLOB:
            raise HTTPException(413, "blob exceeds quota")
        with db() as conn:
            conn.execute("BEGIN IMMEDIATE")
            current = conn.execute("SELECT revision FROM workspaces WHERE account_id = ?", (account,)).fetchone()
            revision = current[0] if current else 0
            if revision != update.revision:
                return JSONResponse({"revision": revision, "detail": "revision conflict"}, status_code=409)
            conn.execute("""INSERT INTO workspaces VALUES (?, ?, ?) ON CONFLICT(account_id) DO UPDATE
                            SET revision = excluded.revision, blob = excluded.blob""", (account, revision + 1, payload))
        return {"revision": revision + 1}

    def read_manifest():
        try:
            with (config.updates_dir / "version.json").open("rb") as manifest_file:
                manifest = UpdateManifest.model_validate_json(manifest_file.read(16384))
            if not APK_NAME.fullmatch(manifest.file) or not RELEASE_TAG.fullmatch(manifest.tag):
                raise ValueError("invalid release names")
            if not (config.updates_dir / manifest.file).is_file():
                raise FileNotFoundError()
            return manifest
        except (OSError, ValueError):
            raise HTTPException(503, "update unavailable") from None

    def github_changelog(manifest):
        now = int(time.time())
        with db() as conn:
            cached = conn.execute("SELECT checked, etag, changelog FROM release_cache WHERE tag = ?",
                                  (manifest.tag,)).fetchone()
        fallback = cached[2] if cached else manifest.changelog
        if (cached and now - cached[0] < 1800) or not release_lock.acquire(blocking=False):
            return fallback
        try:
            headers = {"Accept": "application/vnd.github+json", "User-Agent": "Axis-Backend"}
            etag = cached[1] if cached else ""
            changelog = fallback
            if etag:
                headers["If-None-Match"] = etag
            request = GitHubRequest(f"https://api.github.com/repos/kittkanev/Axis/releases/tags/{manifest.tag}", headers=headers)
            try:
                with urlopen(request, timeout=4) as response:
                    data = json.loads(response.read(65536))
                    if isinstance(data.get("body"), str):
                        changelog = data["body"][:8192]
                    etag = response.headers.get("ETag", "")[:256]
            except HTTPError as error:
                error.close()
            except (URLError, OSError, ValueError):
                pass
            with db() as conn:
                conn.execute("INSERT OR REPLACE INTO release_cache VALUES (?, ?, ?, ?)",
                             (manifest.tag, now, etag, changelog))
            return changelog
        finally:
            release_lock.release()

    @app.get("/version.json")
    def version():
        manifest = read_manifest()
        return JSONResponse({"versionCode": manifest.versionCode, "versionName": manifest.versionName,
                             "packageName": manifest.packageName, "sha256": manifest.sha256,
                             "url": f"/apk/{manifest.file}", "changelog": github_changelog(manifest)},
                            headers={"Cache-Control": "public, max-age=300"})

    @app.get("/apk/{filename}")
    def download_apk(filename: str):
        manifest = read_manifest()
        if filename != manifest.file:
            raise HTTPException(404, "APK not found")
        return FileResponse(config.updates_dir / manifest.file, filename=manifest.file,
                            media_type="application/vnd.android.package-archive")

    return app
