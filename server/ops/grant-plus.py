import argparse
import hashlib
import sqlite3
import time
from datetime import datetime, timezone


parser = argparse.ArgumentParser(description="Grant Axis Plus after verifying the payment in the provider dashboard")
parser.add_argument("email")
parser.add_argument("--reference", required=True, help="Unique provider transaction reference")
parser.add_argument("--days", type=int, default=30, choices=range(1, 366), metavar="1-365")
options = parser.parse_args()
if not options.reference.strip():
    parser.error("payment reference must be nonempty")
reference = hashlib.sha256(options.reference.strip().encode()).hexdigest()
with sqlite3.connect("/var/lib/axis/axis.db", timeout=10) as conn:
    conn.execute("PRAGMA foreign_keys=ON")
    conn.execute("BEGIN IMMEDIATE")
    match = conn.execute("SELECT id FROM accounts WHERE email=?", (options.email.lower().strip(),)).fetchone()
    if not match:
        raise SystemExit("Axis account not found")
    account = match[0]
    if conn.execute("SELECT 1 FROM plan_payments WHERE reference=?", (reference,)).fetchone():
        raise SystemExit("That transaction was already applied")
    now = int(time.time())
    expiry = conn.execute("SELECT expires FROM entitlements WHERE account_id=?", (account,)).fetchone()
    expiration = max(expiry[0] if expiry else 0, now) + options.days * 86400
    conn.execute("INSERT INTO plan_payments VALUES (?,?,?)", (reference, account, now))
    conn.execute("INSERT INTO entitlements VALUES (?,?) ON CONFLICT(account_id) DO UPDATE SET expires=excluded.expires",
                 (account, expiration))
print("Plus active through", datetime.fromtimestamp(expiration, timezone.utc).isoformat())
