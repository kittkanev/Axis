import runpy
import secrets
from pathlib import Path


call = runpy.run_path(str(Path(__file__).with_name("smoke-api.py")))["call"]
credentials = {"email": f"tools-{secrets.token_hex(5)}@axis.invalid", "password": secrets.token_urlsafe(24)}
token = call("POST", "/auth/register", 201, credentials)["access_token"]
cases = (
    ("search", {"query": "Kotlin official Android Jetpack Compose"}, "https://"),
    ("read_url", {"url": "https://en.wikipedia.org/wiki/Argon2"}, "Argon2"),
    ("weather", {"city": "Moscow"}, "Open-Meteo"),
    ("currency", {"base": "USD", "target": "EUR"}, "Frankfurter"),
    ("wiki", {"title": "Moscow", "language": "en"}, "Moscow"),
)
for name, payload, expected in cases:
    result = call("POST", f"/tools/{name}", 200, payload, token)
    assert expected in result["text"], f"Unexpected {name} result"
for address in ("http://127.0.0.1/", "http://169.254.169.254/", "http://[::ffff:127.0.0.1]/", "http://example.com:8080/"):
    call("POST", "/tools/read_url", 422, {"url": address}, token)
assert call("POST", "/tools/wiki", 200, cases[-1][1], token)["text"]
