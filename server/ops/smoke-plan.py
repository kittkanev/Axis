import json
import runpy
import secrets
import subprocess
from pathlib import Path


directory = Path(__file__).parent
call = runpy.run_path(str(directory / "smoke-api.py"))["call"]
email = f"plan-{secrets.token_hex(5)}@axis.invalid"
credentials = {"email": email, "password": secrets.token_urlsafe(24)}
token = call("POST", "/auth/register", 201, credentials)["access_token"]
token = call("POST", "/auth/renew", 200, {}, token)["access_token"]
free = call("GET", "/subscription", 200, token=token)
assert free["tier"] == "free" and free["limit"] == free["remaining"] == 10
assert call("GET", "/provider/v1/models", 200, token=token)["data"][0]["id"] == free["model"]
request = {"model": free["model"], "stream": True,
           "messages": [{"role": "user", "content": "Reply with exactly AXIS-OK. This is a connectivity test."}]}
events = call("POST", "/provider/v1/chat/completions", 200, request, token, binary=True)
chunks = [line.removeprefix(b"data:").strip() for line in events.splitlines() if line.startswith(b"data:")]
text = []
for chunk in chunks:
    if chunk != b"[DONE]":
        choice = json.loads(chunk).get("choices") or []
        if choice:
            text.append(choice[0].get("delta", {}).get("content") or "")
assert "AXIS-OK" in "".join(text).upper() and b"[DONE]" in chunks
assert call("GET", "/subscription", 200, token=token)["remaining"] == 9
command = ["python3", str(directory / "grant-plus.py"), email, "--reference", "test-" + secrets.token_hex(10)]
subprocess.run(command, check=True)
assert subprocess.run(command, capture_output=True).returncode != 0
plus = call("GET", "/subscription", 200, token=token)
assert plus["tier"] == "plus" and plus["limit"] == 60 and plus["remaining"] == 59 and plus["expiresAt"]
print("Live Coarena DeepSeek SSE, Free debit, Plus activation, and duplicate payment detection verified")
