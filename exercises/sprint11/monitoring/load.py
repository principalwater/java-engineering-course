#!/usr/bin/env python3
"""Конечная HTTP-нагрузка: ограничены время, потоки и число ожидающих запросов."""
import argparse
from concurrent.futures import ThreadPoolExecutor, wait, FIRST_COMPLETED
import json
import random
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen

BASE = "http://127.0.0.1:19321"
MAX_PENDING = 64


def request(path, payload=None):
    data = None if payload is None else json.dumps(payload).encode()
    try:
        with urlopen(Request(BASE + path, data=data,
                             headers={"Content-Type": "application/json"}), timeout=10) as response:
            return response.status, response.read()
    except HTTPError as error:
        with error:
            return error.code, error.read()


def run(seconds, orders_per_second, reads_per_second):
    if not 1 <= seconds <= 300 or not 0 <= orders_per_second <= 20 or not 0 <= reads_per_second <= 50:
        raise ValueError("Load limits exceeded")
    rng = random.Random(42)
    pending, created, read = set(), 0, 0

    def collect(done):
        nonlocal created, read
        for future in done:
            status, _ = future.result()
            if status == 201:
                created += 1
            elif status == 200:
                read += 1
            else:
                raise AssertionError(f"Unexpected HTTP status under load: {status}")

    started = time.monotonic()
    with ThreadPoolExecutor(max_workers=32) as pool:
        for tick in range(seconds):
            requests = [None] * reads_per_second
            for index in range(orders_per_second):
                requests.append({"username": f"study-{tick}-{index}", "orderType":
                    rng.choices(("BROWSER", "MOBILE", "OTHER"), weights=(3, 5, 2))[0]})
            for payload in requests:
                if len(pending) >= MAX_PENDING:
                    done, pending = wait(pending, return_when=FIRST_COMPLETED)
                    collect(done)
                pending.add(pool.submit(request, "/orders", payload))
            done = {future for future in pending if future.done()}
            collect(done)
            pending -= done
            time.sleep(max(0, started + tick + 1 - time.monotonic()))
        collect(pending)
    assert created == seconds * orders_per_second
    assert read == seconds * reads_per_second
    return {"created": created, "read": read, "elapsed_seconds": round(time.monotonic() - started, 2)}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seconds", type=int, default=20)
    parser.add_argument("--orders-per-second", type=int, default=15)
    parser.add_argument("--reads-per-second", type=int, default=35)
    arguments = parser.parse_args()
    print(json.dumps(run(arguments.seconds, arguments.orders_per_second, arguments.reads_per_second)))
