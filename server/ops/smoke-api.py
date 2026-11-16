import base64
import hashlib
import json
import secrets
import subprocess


CA = "/var/lib/caddy/.local/share/caddy/pki/authorities/local/root.crt"
BASE = "https://localhost:8443"


def call(method, path, status, body=None, token=None, binary=False):
    settings = []
    if body is not None:
        settings += ['header = "Content-Type: application/json"', 'data-binary = ' + json.dumps(json.dumps(body))]
    if token:
        settings.append('header = ' + json.dumps('Authorization: Bearer ' + token))
    result = subprocess.run(["curl", "--noproxy", "*", "--silent", "--show-error", "--max-time", "15",
                             "--cacert", CA, "--request", method, "--config", "-", "--write-out", "\n%{http_code}",
                             BASE + path], input="\n".join(settings).encode(), capture_output=True, check=True)
    data, _, actual = result.stdout.rpartition(b"\n")
    assert int(actual) == status, f"{method} {path} returned {actual.decode()}, expected {status}"
    print(f"{method} {path} -> {status}")
    return data if binary else json.loads(data)


def main():
    assert call("GET", "/healthz", 200) == {"status": "ok"}
    creds = {"email": f"smoke-{secrets.token_hex(5)}@axis.invalid", "password": secrets.token_urlsafe(24)}
    call("POST", "/auth/register", 201, creds)
    token = call("POST", "/auth/login", 200, creds)["access_token"]
    call("POST", "/auth/login", 401, {**creds, "password": secrets.token_urlsafe(24)})
    call("GET", "/sync/blob", 401)
    call("GET", "/sync/blob", 401, token="invalid-token")
    assert call("GET", "/sync/blob", 200, token=token) == {"revision": 0, "blob": None}
    payload = base64.b64encode(secrets.token_bytes(48)).decode()
    assert call("PUT", "/sync/blob", 200, {"revision": 0, "blob": payload}, token) == {"revision": 1}
    assert call("PUT", "/sync/blob", 409, {"revision": 0, "blob": payload}, token)["revision"] == 1
    assert call("GET", "/sync/blob", 200, token=token) == {"revision": 1, "blob": payload}
    other = {"email": f"smoke-{secrets.token_hex(5)}@axis.invalid", "password": secrets.token_urlsafe(24)}
    other_token = call("POST", "/auth/register", 201, other)["access_token"]
    assert call("GET", "/sync/blob", 200, token=other_token) == {"revision": 0, "blob": None}
    manifest = call("GET", "/version.json", 200)
    apk = call("GET", manifest["url"], 200, binary=True)
    assert hashlib.sha256(apk).hexdigest() == manifest["sha256"]
    second = call("GET", "/version.json", 200)
    assert manifest == second


if __name__ == "__main__":
    main()
