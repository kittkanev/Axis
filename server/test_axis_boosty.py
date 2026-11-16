import asyncio
import re
import secrets
import sqlite3
import time
from collections import deque

from fastapi.testclient import TestClient

from axis_boosty import BoostyTelegram, Unavailable
from axis_server import Config, create_app


CHAT = -1001234567890
BOT = 123456
BUYER = 840123


def test_paid_tier_claim_and_recheck(tmp_path, monkeypatch):
    monkeypatch.setenv("AXIS_BOOSTY_BOT_TOKEN", f"{BOT}:" + "test_only_value_" * 3)
    monkeypatch.setenv("AXIS_BOOSTY_BOT_USERNAME", "AxisPlus_TestBot")
    monkeypatch.setenv("AXIS_BOOSTY_CHAT_ID", str(CHAT))
    monkeypatch.delenv("AXIS_PAYMENT_URL", raising=False)
    monkeypatch.setenv("AXIS_BOOSTY_CHECKOUT_URL", "https://boosty.to/axis-test/purchase/123")
    monkeypatch.delenv("AXIS_BOOSTY_CHECKOUT_ENABLED", raising=False)
    monkeypatch.delenv("AXIS_NTFY_TOKEN", raising=False)
    updates = deque()
    messages = deque()
    membership = "member"

    async def telegram(_self, method, payload=None):
        if method == "getMe":
            return {"id": BOT, "username": "AxisPlus_TestBot"}
        if method == "getChat":
            assert payload["chat_id"] == CHAT
            return {"type": "supergroup"}
        if method == "getChatAdministrators":
            return [{"user": {"username": "boosty_to_bot", "is_bot": True}}]
        if method == "getChatMember":
            assert payload["chat_id"] == CHAT
            user = payload["user_id"]
            if user != BOT and membership == "unreachable":
                raise Unavailable
            return {"user": {"id": user, "is_bot": user == BOT}, "status": "administrator" if user == BOT else membership}
        if method == "getUpdates":
            await asyncio.sleep(0.01)
            return [updates.popleft()] if updates else []
        if method == "sendMessage":
            assert payload["chat_id"] == BUYER
            messages.append(payload["text"])
            return {}
        raise AssertionError(method)

    def eventually(test):
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            result = test()
            if result:
                return result
            time.sleep(0.02)
        raise AssertionError("Expected Telegram update did not finish")

    def issue(id):
        updates.append({"update_id": id, "message": {"chat": {"id": BUYER, "type": "private"},
                        "from": {"id": BUYER, "is_bot": False}, "text": "/code"}})
        reply = eventually(lambda: messages.popleft() if messages else None)
        return re.search(r"\b[a-f0-9]{16}\b", reply)

    def overdue(expire=False):
        with sqlite3.connect(tmp_path / "axis.db") as conn:
            conn.execute("UPDATE boosty_telegram_links SET check_after=0")
            if expire:
                conn.execute("UPDATE boosty_telegram_links SET verified_until=0")

    monkeypatch.setattr(BoostyTelegram, "api", telegram)
    with TestClient(create_app(Config(tmp_path, tmp_path, secrets.token_bytes(32)))) as client:
        def account(name):
            response = client.post("/auth/register", json={"email": name + "@example.test",
                                   "password": secrets.token_urlsafe(24)})
            assert response.status_code == 201
            return {"Authorization": f"Bearer {response.json()['access_token']}"}

        buyer, other = account("buyer"), account("other")
        status_url = "/subscription"
        claim_url = "/subscription/boosty/claim"
        assert eventually(lambda: client.get(status_url, headers=buyer).json()["boostyBotUrl"]) == "https://t.me/AxisPlus_TestBot"
        first = client.get(status_url, headers=buyer).json()
        assert first["tier"] == "free" and first["checkoutUrl"] is None
        membership = "left"
        assert issue(1) is None
        membership = "member"
        code = issue(2).group()
        assert client.post(claim_url, json={"code": code}).status_code == 401
        assert client.post(claim_url, headers=buyer, json={"code": code, "telegramId": BUYER}).status_code == 422
        assert client.post(claim_url, headers=buyer, json={"code": "0" * 16}).status_code == 404
        paid = client.post(claim_url, headers=buyer, json={"code": code.upper()})
        assert paid.status_code == 200
        assert paid.json()["tier"] == "plus" and paid.json()["limit"] == 60
        assert paid.json()["source"] == "boosty" and paid.json()["expiresAt"] is None
        assert client.post(claim_url, headers=other, json={"code": code}).status_code == 404
        second = issue(3).group()
        assert client.post(claim_url, headers=other, json={"code": second}).status_code == 409

        overdue()
        membership = "unreachable"
        assert client.get(status_url, headers=buyer).json()["tier"] == "plus"
        overdue(expire=True)
        assert client.get(status_url, headers=buyer).json()["tier"] == "free"
        membership = "member"
        overdue()
        assert client.get(status_url, headers=buyer).json()["tier"] == "plus"
        membership = "left"
        updates.append({"update_id": 4, "chat_member": {"chat": {"id": CHAT},
                        "new_chat_member": {"user": {"id": BUYER}, "status": "left"}}})
        assert eventually(lambda: client.get(status_url, headers=buyer).json()["tier"] == "free")
        overdue()
        membership = "member"
        assert client.get(status_url, headers=buyer).json()["tier"] == "plus"


def test_checkout_requires_ready_bridge(tmp_path, monkeypatch):
    monkeypatch.setenv("AXIS_BOOSTY_CHECKOUT_URL", "https://boosty.to/axis-test/purchase/123")
    monkeypatch.setenv("AXIS_BOOSTY_CHECKOUT_ENABLED", "1")
    for key in ("AXIS_BOOSTY_BOT_TOKEN", "AXIS_BOOSTY_BOT_USERNAME", "AXIS_BOOSTY_CHAT_ID", "AXIS_NTFY_TOKEN"):
        monkeypatch.delenv(key, raising=False)
    with TestClient(create_app(Config(tmp_path, tmp_path, secrets.token_bytes(32)))) as client:
        response = client.post("/auth/register", json={"email": "buyer@example.test", "password": secrets.token_urlsafe(24)})
        assert response.status_code == 201
        headers = {"Authorization": f"Bearer {response.json()['access_token']}"}
        assert client.get("/subscription", headers=headers).json()["checkoutUrl"] is None
