#!/usr/bin/env python3
"""Viewport query benchmark: p50/p95/p99 latency of GET /api/v1/assets?bbox=...

Boxes are chosen around the data area so that between a few hundred and 1,000 assets match
(the target: 95% within 100 ms when up to 1,000 assets match). Standard library only.

  python3 scripts/bench_viewport.py --username op01 --password '...' --requests 500 --concurrency 4
"""
import argparse
import http.client
import json
import os
import random
import statistics
import threading
import time
from urllib.parse import urlparse


def login(url, user, password):
    c = http.client.HTTPConnection(url.hostname, url.port or 80, timeout=10)
    c.request("POST", "/api/v1/auth/login", json.dumps({"username": user, "password": password}),
              {"Content-Type": "application/json"})
    r = c.getresponse()
    body = r.read()
    if r.status != 200:
        raise SystemExit("login failed: %d %s" % (r.status, body[:200]))
    return json.loads(body)["accessToken"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://localhost:8080")
    ap.add_argument("--username", default=os.environ.get("BENCH_USERNAME", "op01"))
    ap.add_argument("--password", default=os.environ.get("BENCH_PASSWORD"))
    ap.add_argument("--requests", type=int, default=500)
    ap.add_argument("--concurrency", type=int, default=4)
    ap.add_argument("--box-deg", type=float, default=4.0, help="box size in degrees")
    args = ap.parse_args()
    url = urlparse(args.base_url)
    token = login(url, args.username, args.password)

    latencies, matched, lock = [], [], threading.Lock()
    per_worker = args.requests // args.concurrency

    def worker(seed):
        rnd = random.Random(seed)
        c = http.client.HTTPConnection(url.hostname, url.port or 80, timeout=10)
        for _ in range(per_worker):
            lon = rnd.uniform(166.0, 182.0)
            lat = rnd.uniform(31.0, 41.0)
            min_lon = (lon + 180) % 360 - 180
            max_lon = (lon + args.box_deg + 180) % 360 - 180
            bbox = "%.4f,%.4f,%.4f,%.4f" % (min_lon, lat, max_lon, lat + args.box_deg)
            t0 = time.perf_counter()
            c.request("GET", "/api/v1/assets?bbox=%s&limit=1000" % bbox, headers={"Authorization": "Bearer " + token})
            r = c.getresponse()
            body = r.read()
            ms = (time.perf_counter() - t0) * 1000
            if r.status != 200:
                raise SystemExit("HTTP %d: %s" % (r.status, body[:200]))
            n = len(json.loads(body)["items"])
            with lock:
                latencies.append(ms)
                matched.append(n)

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(args.concurrency)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    latencies.sort()
    q = statistics.quantiles(latencies, n=100)
    print(json.dumps({
        "requests": len(latencies), "concurrency": args.concurrency,
        "matchedAssets": {"min": min(matched), "median": statistics.median(matched), "max": max(matched)},
        "latencyMs": {"p50": round(q[49], 1), "p95": round(q[94], 1), "p99": round(q[98], 1), "max": round(latencies[-1], 1)},
        "within100msPct": round(100.0 * sum(1 for x in latencies if x <= 100) / len(latencies), 1),
    }, indent=2))


if __name__ == "__main__":
    main()
