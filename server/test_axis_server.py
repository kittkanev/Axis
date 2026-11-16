import base64
import secrets
import socket
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier

import pytest
from fastapi.testclient import TestClient

from axis_server import MAX_BLOB, Config, create_app
from axis_webtools import InvalidAddress, public_addresses


def test_revision_quota_and_concurrent_compare_and_set(tmp_path):
    client = TestClient(create_app(Config(tmp_path, tmp_path, secrets.token_bytes(32))))
    credentials = {"email": "sync@example.test", "password": secrets.token_urlsafe(24)}
    response = client.post("/auth/register", json=credentials)
    assert response.status_code == 201
    token = response.json()["access_token"]
    headers = {"Authorization": f"Bearer {token}"}
    payload = base64.b64encode(bytes(MAX_BLOB + 1)).decode()
    too_large = client.put("/sync/blob", json={"revision": 0, "blob": payload}, headers=headers)
    assert too_large.status_code == 413
    barrier = Barrier(2)

    def store(blob):
        barrier.wait(timeout=3)
        response = client.put("/sync/blob", json={"revision": 0, "blob": blob}, headers=headers)
        return response.status_code, blob

    with ThreadPoolExecutor(2) as pool:
        results = dict(pool.map(store, ("AAAA", "AAAB")))
    assert set(results) == {200, 409}
    assert client.get("/sync/blob", headers=headers).json() == {"revision": 1, "blob": results[200]}


def test_auth_rate_limit_and_rejected_password_not_reflected(tmp_path):
    client = TestClient(create_app(Config(tmp_path, tmp_path, secrets.token_bytes(32))))
    rejected = "secret"
    result = client.post("/auth/login", json={"email": "someone@example.test", "password": rejected})
    assert result.status_code == 422 and rejected not in result.text
    for _ in range(8):
        result = client.post("/auth/login", json={"email": "someone@example.test", "password": "incorrect-password"})
        assert result.status_code == 401
    assert client.post("/auth/login", json={"email": "someone@example.test", "password": "incorrect-password"}).status_code == 429


def test_url_dns_rejects_mixed_public_and_private_records(monkeypatch):
    monkeypatch.setattr(socket, "getaddrinfo", lambda *args, **kwargs: [
        (socket.AF_INET, socket.SOCK_STREAM, 6, "", ("1.1.1.1", 443)),
        (socket.AF_INET, socket.SOCK_STREAM, 6, "", ("192.168.0.2", 443)),
    ])
    with pytest.raises(InvalidAddress):
        public_addresses("mixed.example.test", 443)
