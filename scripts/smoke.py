#!/usr/bin/env python3
"""Smoke test of a deployed stack through the load balancer (both instances behind it).

  python3 scripts/smoke.py --users deploy/secrets/users.json [--base-url http://localhost:8080]

Checks: health, sign-in for every user, one exact scenario (enter zone -> one alert), redelivery,
a late report, 20 operators claiming at once, the audit trail, a mixed good/bad batch, and that the
console page is served with a CSP that forbids other hosts. Exit code 0 when everything passes.
"""
import argparse
import http.client
import json
import threading
import time
import uuid
from urllib.parse import urlparse

URL = None
failures = []


def call(method, path, token=None, body=None, ctype="application/json"):
    headers = {}
    if body is not None:
        headers["Content-Type"] = ctype
    if token:
        headers["Authorization"] = "Bearer " + token
    c = http.client.HTTPConnection(URL.hostname, URL.port or 80, timeout=30)
    c.request(method, path, body=body, headers=headers)
    r = c.getresponse()
    data = r.read()
    hdrs = dict(r.getheaders())
    c.close()
    try:
        parsed = json.loads(data) if data and "json" in hdrs.get("Content-Type", "") else data
    except ValueError:
        parsed = data
    return r.status, parsed, hdrs


def check(name, cond, detail=""):
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else "  " + str(detail)[:300]))
    if not cond:
        failures.append(name)


def report(asset, second, lat, lon):
    return json.dumps({"reportId": uuid.uuid4().hex, "assetId": asset, "kind": "SURFACE",
                       "observedAt": "2026-11-02T07:%02d:%02d.000Z" % (second // 60, second % 60),
                       "lat": lat, "lon": lon, "headingDeg": 45.0, "speedKn": 12.0, "source": "SAT-1"})


def main():
    global URL
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://localhost:8080")
    ap.add_argument("--users", required=True)
    args = ap.parse_args()
    URL = urlparse(args.base_url)
    users = json.load(open(args.users, encoding="utf-8"))

    s, body, _ = call("GET", "/health")
    check("health is UP", s == 200 and body.get("status") == "UP", body)

    tokens = {}
    for u in users:
        s, body, _ = call("POST", "/api/v1/auth/login", body=json.dumps({"username": u["username"], "password": u["password"]}))
        if s == 200:
            tokens[u["username"]] = body["accessToken"]
    check("every seeded user can sign in", len(tokens) == len(users), "%d of %d" % (len(tokens), len(users)))
    feed = next(t for n, t in tokens.items() if any(u["username"] == n and u["role"] == "INGEST" for u in users))
    ops = [u["username"] for u in users if u["role"] == "OPERATOR"]
    op = tokens[ops[0]]

    asset = "SMK-" + uuid.uuid4().hex[:8].upper()
    enter = report(asset, 2, 38.3, 168.4)
    lines = [report(asset, 1, 37.0, 168.4), enter, "{broken", json.dumps({"reportId": "x"})]
    s, body, _ = call("POST", "/api/v1/reports/batch", feed, "\n".join(lines) + "\n", "application/x-ndjson")
    check("mixed batch: 2 accepted, 2 rejected", s == 200 and body["accepted"] == 2 and body["rejected"] == 2, body)

    s, body, _ = call("POST", "/api/v1/reports/batch", feed, enter + "\n", "application/x-ndjson")
    check("redelivery is a duplicate", s == 200 and body["duplicates"] == 1, body)

    s, body, _ = call("POST", "/api/v1/reports/batch", feed, report(asset, 0, 38.31, 168.41) + "\n", "application/x-ndjson")
    s, pos, _ = call("GET", "/api/v1/assets/" + asset, op)
    check("late report does not move the position", pos.get("observedAt") == "2026-11-02T07:00:02.000Z", pos)

    time.sleep(0.5)
    s, page, _ = call("GET", "/api/v1/alerts?assetId=" + asset, op)
    check("entering Z-01 raised exactly one alert", s == 200 and len(page["items"]) == 1, page)
    if not page.get("items"):
        raise SystemExit(1)
    alert_id = page["items"][0]["alertId"]

    results = []
    lock = threading.Lock()

    def claim(name):
        st, b, _ = call("POST", "/api/v1/alerts/%s/claim" % alert_id, tokens[name], "")
        with lock:
            results.append((st, b))

    threads = [threading.Thread(target=claim, args=(n,)) for n in ops]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    wins = [r for r in results if r[0] == 200]
    lost = [r for r in results if r[0] == 409 and r[1].get("code") == "ALERT_ALREADY_CLAIMED"]
    check("%d simultaneous claims: exactly one winner" % len(ops), len(wins) == 1 and len(lost) == len(ops) - 1,
          [r[0] for r in results])

    owner = wins[0][1]["claimedBy"] if wins else ops[0]
    s, body, _ = call("POST", "/api/v1/alerts/%s/resolve" % alert_id, tokens[owner], json.dumps({"note": "smoke test"}))
    check("owner resolves", s == 200 and body["status"] == "RESOLVED", body)
    s, audit, _ = call("GET", "/api/v1/alerts/%s/audit" % alert_id, op)
    check("audit trail OPENED, CLAIMED, RESOLVED",
          [a["action"] for a in audit["items"]] == ["OPENED", "CLAIMED", "RESOLVED"], audit)

    s, body, hdrs = call("GET", "/")
    csp = hdrs.get("Content-Security-Policy", "")
    check("console served with a same-origin CSP", s == 200 and "connect-src 'self'" in csp, csp)

    s, stats, _ = call("GET", "/api/v1/stats", feed)
    check("stats answer", s == 200 and stats["accepted"] >= 3, stats)

    print("\n%d failed" % len(failures))
    raise SystemExit(1 if failures else 0)


if __name__ == "__main__":
    main()
