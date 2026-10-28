#!/usr/bin/env python3
"""Send repeatable catalog, checkout, and order reads to the local commerce fixture."""

import argparse
import json
import time
import urllib.request


def request(base, path, body=None):
    data = None if body is None else json.dumps(body).encode("utf-8")
    headers = {"Content-Type": "application/json"} if data is not None else {}
    outgoing = urllib.request.Request(base + path, data=data, headers=headers)
    with urllib.request.urlopen(outgoing, timeout=8) as response:
        return response.status, json.load(response)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="http://127.0.0.1:61218")
    parser.add_argument("--duration", type=int, default=180, help="Seconds to send traffic")
    parser.add_argument("--interval", type=float, default=1.0, help="Seconds between requests")
    args = parser.parse_args()
    if args.duration < 1 or args.interval <= 0:
        parser.error("duration and interval must be positive")
    base = args.base.rstrip("/")
    status, health = request(base, "/health")
    if status != 200 or health.get("status") != "UP":
        raise RuntimeError("Commerce fixture is not healthy")

    skus = ("BOOK-JAVA", "MUG-TRACE", "KIT-OPS")
    started = time.monotonic()
    next_request = started
    requests = orders = errors = 0
    last_order = None
    last_report = started
    print(f"START base={base} duration={args.duration}s interval={args.interval}s", flush=True)
    while time.monotonic() - started < args.duration:
        step = requests % 4
        try:
            if step == 0:
                status, value = request(base, "/shop/catalog")
                if status != 200 or len(value) < 3:
                    raise RuntimeError("catalog response incomplete")
            elif step == 1:
                sku = skus[orders % len(skus)]
                status, value = request(base, "/shop/orders", {
                    "customerId": f"demo-customer-{orders % 30 + 1:04d}",
                    "sku": sku,
                    "quantity": orders % 3 + 1,
                })
                if status != 201 or value.get("status") != "PLACED":
                    raise RuntimeError("checkout response incomplete")
                last_order = value["id"]
                orders += 1
            elif step == 2:
                status, value = request(base, "/shop/orders/" + last_order)
                if status != 200 or value.get("id") != last_order:
                    raise RuntimeError("order detail does not match checkout")
            else:
                status, value = request(base, "/shop/orders")
                if status != 200 or not any(row.get("id") == last_order for row in value):
                    raise RuntimeError("recent orders omitted the new order")
        except Exception as failure:
            errors += 1
            print(f"ERROR step={step} {type(failure).__name__}: {failure}", flush=True)
        requests += 1
        now = time.monotonic()
        if now - last_report >= 20:
            print(f"PROGRESS elapsed={round(now-started)}s requests={requests} orders={orders} errors={errors}", flush=True)
            last_report = now
        next_request += args.interval
        remaining = next_request - time.monotonic()
        if remaining > 0:
            time.sleep(remaining)
    print(f"DONE elapsed={round(time.monotonic()-started)}s requests={requests} orders={orders} errors={errors}", flush=True)
    return 0 if errors == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
