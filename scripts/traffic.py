#!/usr/bin/env python3
"""Steady mixed API traffic that counts every failed request. Used to prove the rolling upgrade
(INSTALL.md, section C) causes no failed request. Run it alongside replay.py; stop with Ctrl-C.

  python3 scripts/traffic.py --password-file users.json

A request fails when it does not answer, or answers 5xx. 4xx answers that the contract defines for
the request (e.g. 409 on a lost claim race) are expected and counted separately.
"""
import argparse
import http.client
import json
import random
import threading
import time
from urllib.parse import urlparse

stats = {"ok": 0, "expected4xx": 0, "failed": 0}
failures = []
lock = threading.Lock()
stop = threading.Event()


def call(url, method, path, token=None, body=None, ctype="application/json"):
    headers = {"Content-Type": ctype} if body is not None else {}
    if token:
        headers["Authorization"] = "Bearer " + token
    try:
        c = http.client.HTTPConnection(url.hostname, url.port or 80, timeout=15)
        c.request(method, path, body=body, headers=headers)
        r = c.getresponse()
        data = r.read()
        c.close()
        return r.status, data
    except Exception as e:  # noqa: BLE001 - every transport error is a failed request
        return None, repr(e).encode()


def record(method, path, status, data, expected):
    with lock:
        if status is not None and 200 <= status < 300:
            stats["ok"] += 1
        elif status in expected:
            stats["expected4xx"] += 1
        else:
            stats["failed"] += 1
            failures.append("%s %s -> %s %s" % (method, path, status, data[:120]))


def reader(url, token):
    paths = ["/api/v1/stats", "/api/v1/assets?bbox=166,30,180,42&limit=1000", "/api/v1/alerts?status=OPEN&limit=50",
             "/health"]
    while not stop.is_set():
        p = random.choice(paths)
        s, d = call(url, "GET", p, token)
        record("GET", p, s, d, ())
        time.sleep(0.05)


def claimer(url, token):
    while not stop.is_set():
        s, d = call(url, "GET", "/api/v1/alerts?status=OPEN&limit=20", token)
        record("GET", "/api/v1/alerts", s, d, ())
        if s == 200:
            for a in json.loads(d)["items"][:2]:
                p = "/api/v1/alerts/%s/claim" % a["alertId"]
                s2, d2 = call(url, "POST", p, token, "")
                record("POST", p, s2, d2, (409,))
                if s2 == 200:
                    p = "/api/v1/alerts/%s/resolve" % a["alertId"]
                    s3, d3 = call(url, "POST", p, token, json.dumps({"note": "checked during upgrade"}))
                    record("POST", p, s3, d3, (409,))
        time.sleep(0.2)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://localhost:8080")
    ap.add_argument("--password-file", required=True, help="users JSON file (to sign in operators)")
    ap.add_argument("--seconds", type=int, default=0, help="stop after this many seconds (0 = Ctrl-C)")
    args = ap.parse_args()
    url = urlparse(args.base_url)
    users = {u["username"]: u for u in json.load(open(args.password_file, encoding="utf-8"))}
    tokens = []
    for name in ("op01", "op02", "sup01"):
        s, d = call(url, "POST", "/api/v1/auth/login", None,
                    json.dumps({"username": name, "password": users[name]["password"]}))
        if s != 200:
            raise SystemExit("login failed for %s: %s" % (name, s))
        tokens.append(json.loads(d)["accessToken"])
    threads = [threading.Thread(target=reader, args=(url, tokens[0]), daemon=True),
               threading.Thread(target=reader, args=(url, tokens[2]), daemon=True),
               threading.Thread(target=claimer, args=(url, tokens[1]), daemon=True)]
    for t in threads:
        t.start()
    started = time.time()
    try:
        while not args.seconds or time.time() - started < args.seconds:
            time.sleep(5)
            with lock:
                print("  %s" % json.dumps(stats), flush=True)
    except KeyboardInterrupt:
        pass
    stop.set()
    for t in threads:
        t.join(timeout=20)
    print("final: %s" % json.dumps(stats))
    for f in failures[:20]:
        print("  FAILED " + f)
    raise SystemExit(1 if stats["failed"] else 0)


if __name__ == "__main__":
    main()
