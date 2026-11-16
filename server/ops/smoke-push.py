import json
import runpy
import secrets
import subprocess
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path


call = runpy.run_path(str(Path(__file__).with_name("smoke-api.py")))["call"]


def account():
    creds = {"email": f"push-{secrets.token_hex(5)}@axis.invalid", "password": secrets.token_urlsafe(24)}
    return call("POST", "/auth/register", 201, creds)["access_token"]


token, other = account(), account()
denied = subprocess.check_output(["curl", "-sS", "-o", "/dev/null", "-w", "%{http_code}", "--max-time", "5",
                                  "http://127.0.0.1:2586/axis_unauthorized/json?poll=1"]).decode()
assert denied in {"401", "403"}, denied
print(f"Anonymous ntfy subscription blocked -> {denied}")
call("GET", "/push/events?poll=true", 401)
due = (datetime.now(timezone.utc) + timedelta(seconds=6)).isoformat()
identifier = call("POST", "/reminders", 201, {"dueAt": due, "text": "Axis test reminder"}, token)["id"]
time.sleep(9)
events = call("GET", "/push/events?poll=true", 200, token=token, binary=True)
assert {"id": identifier, "text": "Axis test reminder"} in [json.loads(line) for line in events.splitlines()]
other_events = call("GET", "/push/events?poll=true", 200, token=other, binary=True)
assert identifier.encode() not in other_events
print("Scheduled reminder arrived only in the owner's authenticated topic")
