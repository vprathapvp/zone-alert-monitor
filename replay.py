#!/usr/bin/env python3
"""Feed replayer for the Zone Watch software engineering assessment.

Reads feed.ndjson.gz and delivers it to your ingest endpoint in batches over
several parallel connections. Standard library only (Python 3.9+).

The transport is AT-LEAST-ONCE: when a request times out or fails, the same
batch is sent again. Parallel connections mean that batches do NOT arrive in
file order. Your system must be correct under both conditions.

Examples
  python3 replay.py --base-url http://localhost:8080 --username feed --password '...'
  python3 replay.py --speed 5          # 5x real time (about 1,500 lines per second)
  python3 replay.py --speed max        # as fast as your system accepts

Credentials can also be given with FEED_USERNAME and FEED_PASSWORD.
"""
import argparse
import gzip
import http.client
import json
import os
import queue
import sys
import threading
import time
from urllib.parse import urlparse

REAL_TIME_LINES_PER_SEC = 300.0   # 1x speed: the recorded feed carries about 300 lines per second


def login(url, username, password, timeout):
    conn = http.client.HTTPConnection(url.hostname, url.port or 80, timeout=timeout)
    body = json.dumps({"username": username, "password": password})
    conn.request("POST", "/api/v1/auth/login", body=body, headers={"Content-Type": "application/json"})
    resp = conn.getresponse()
    data = resp.read()
    conn.close()
    if resp.status != 200:
        sys.exit("login failed: HTTP %d %s" % (resp.status, data[:300].decode("utf-8", "replace")))
    return json.loads(data)["accessToken"]


def worker(url, token, jobs, totals, lock, timeout, retries):
    conn = None
    while True:
        batch = jobs.get()
        if batch is None:
            return
        payload = ("\n".join(batch) + "\n").encode("utf-8")
        for attempt in range(retries + 1):
            try:
                if conn is None:
                    conn = http.client.HTTPConnection(url.hostname, url.port or 80, timeout=timeout)
                conn.request("POST", "/api/v1/reports/batch", body=payload,
                             headers={"Content-Type": "application/x-ndjson", "Authorization": "Bearer " + token})
                resp = conn.getresponse()
                data = resp.read()
                if resp.status == 200:
                    summary = json.loads(data)
                    with lock:
                        totals["batches"] += 1
                        for key in ("received", "accepted", "duplicates", "rejected"):
                            totals[key] += int(summary.get(key, 0))
                    break
                with lock:
                    totals["httpErrors"] += 1
                    if totals["httpErrors"] <= 5:
                        print("  HTTP %d: %s" % (resp.status, data[:200].decode("utf-8", "replace")), file=sys.stderr)
                if resp.status < 500:
                    break   # a 4xx will not succeed on retry
            except (OSError, http.client.HTTPException, ValueError) as exc:
                with lock:
                    totals["retries"] += 1
                    if totals["retries"] <= 5:
                        print("  retrying after: %r" % (exc,), file=sys.stderr)
                try:
                    conn.close()
                except Exception:
                    pass
                conn = None
            time.sleep(min(2.0, 0.2 * (attempt + 1)))
        else:
            with lock:
                totals["failedBatches"] += 1


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base-url", default="http://localhost:8080")
    ap.add_argument("--file", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "feed.ndjson.gz"))
    ap.add_argument("--username", default=os.environ.get("FEED_USERNAME", "feed"))
    ap.add_argument("--password", default=os.environ.get("FEED_PASSWORD"))
    ap.add_argument("--speed", default="1", help="1, 5, 10 ... or max (default 1 = real time)")
    ap.add_argument("--connections", type=int, default=4)
    ap.add_argument("--batch-size", type=int, default=250, help="lines per request (the API allows up to 1000)")
    ap.add_argument("--timeout", type=float, default=10.0, help="seconds before a request is re-sent")
    ap.add_argument("--retries", type=int, default=5)
    ap.add_argument("--max-lines", type=int, default=0, help="stop after this many lines (0 = whole file)")
    args = ap.parse_args()
    if not args.password:
        sys.exit("give --password or set FEED_PASSWORD")
    url = urlparse(args.base_url)
    rate = None if args.speed == "max" else REAL_TIME_LINES_PER_SEC * float(args.speed)
    token = login(url, args.username, args.password, args.timeout)

    jobs = queue.Queue(maxsize=args.connections * 2)
    totals = {"batches": 0, "received": 0, "accepted": 0, "duplicates": 0, "rejected": 0,
              "httpErrors": 0, "retries": 0, "failedBatches": 0}
    lock = threading.Lock()
    threads = [threading.Thread(target=worker, args=(url, token, jobs, totals, lock, args.timeout, args.retries), daemon=True)
               for _ in range(args.connections)]
    for t in threads:
        t.start()

    started = time.time()
    sent = 0
    last_print = started
    batch = []
    with gzip.open(args.file, "rt", encoding="utf-8", newline="\n") as fh:
        for line in fh:
            line = line.rstrip("\n")
            if not line:
                continue
            batch.append(line)
            sent += 1
            if len(batch) >= args.batch_size:
                jobs.put(batch)
                batch = []
                if rate:
                    ahead = sent / rate - (time.time() - started)
                    if ahead > 0:
                        time.sleep(ahead)
                now = time.time()
                if now - last_print >= 5.0:
                    last_print = now
                    with lock:
                        print("  %7d lines sent, %7d accepted, %6d duplicates, %5d rejected, %.0f lines/s" % (
                            sent, totals["accepted"], totals["duplicates"], totals["rejected"], sent / (now - started)))
            if args.max_lines and sent >= args.max_lines:
                break
    if batch:
        jobs.put(batch)
    for _ in threads:
        jobs.put(None)
    for t in threads:
        t.join()
    elapsed = time.time() - started
    print("done: %d lines in %.1f s (%.0f lines/s)" % (sent, elapsed, sent / elapsed if elapsed else 0))
    print(json.dumps(totals))
    if totals["failedBatches"] or totals["httpErrors"]:
        sys.exit(1)


if __name__ == "__main__":
    main()
