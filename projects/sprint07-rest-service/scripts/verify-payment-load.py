#!/usr/bin/env python3
"""Проверяет recovery под конечной нагрузкой k6 на двух настоящих JAR, PostgreSQL и Redis."""

from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import shutil
import socket
import subprocess
from threading import Lock, Thread
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from runtime import PROJECT, application, configtree, initialize, postgres, redis, run_directory, wait_for
from smoke import MarketProbe

BACKLOG = 80
BLOCKED = 8
FOREGROUND = 20
PAYMENT_DELAY = 0.2


def sql(database, statement):
    command = ["docker", "exec", "-i", database["container"], "psql", "-XAt", "-v", "ON_ERROR_STOP=1",
               "-U", database["user"], "-d", database["user"]]
    query = "SELECT COALESCE(json_agg(row_to_json(q)), '[]'::json) FROM (" + statement + ") q;"
    return json.loads(subprocess.check_output(command, input=query, text=True))


def free_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return str(listener.getsockname()[1])


@contextmanager
def payment_proxy(origin):
    state = {"outage": True, "blocked": set(), "recovery": set(), "active": 0, "peak": 0, "forwarded": 0, "requests": {}}
    lock = Lock()

    class Proxy(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_GET(self):
            self.forward()

        def do_POST(self):
            self.forward()

        def forward(self):
            body = self.rfile.read(int(self.headers.get("Content-Length", 0))) if self.command == "POST" else None
            key = json.loads(body)["referenceId"] if body else None
            with lock:
                if key is not None:
                    state["requests"][key] = state["requests"].get(key, 0) + 1
                rejected = body is not None and (state["outage"] or key in state["blocked"])
                recovery = key in state["recovery"] and not rejected
                if recovery:
                    state["active"] += 1
                    state["peak"] = max(state["peak"], state["active"])
                    state["forwarded"] += 1
            try:
                if rejected:
                    status, payload, content_type = 503, b'{"detail":"Injected payment outage"}', "application/json"
                else:
                    if body:
                        # Задержка внешней сети делает параллельность recovery наблюдаемой; деньги списывает настоящий сервис.
                        time.sleep(PAYMENT_DELAY)
                    request = Request(origin + self.path, data=body, method=self.command,
                                      headers={name: value for name, value in self.headers.items()
                                               if name.lower() not in {"host", "connection", "content-length"}})
                    try:
                        response = urlopen(request, timeout=8)
                    except HTTPError as error:
                        response = error
                    with response:
                        status, payload = response.status, response.read()
                        content_type = response.headers.get("Content-Type", "application/json")
                self.send_response(status)
                self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
            finally:
                if recovery:
                    with lock:
                        state["active"] -= 1

    server = ThreadingHTTPServer(("127.0.0.1", 0), Proxy)
    worker = Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        yield "http://127.0.0.1:" + str(server.server_port), state, lock
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=10)


def environment(service, port, directory, secrets, database):
    tree = configtree(service, secrets, directory / (service + "-config"), database)
    result = {"SERVER_PORT": port, "SPRING_PROFILES_ACTIVE": "postgresql",
              "SPRING_CONFIG_IMPORT": "configtree:" + str(tree) + "/", "STORE_ANALYTICS_ENABLED": "false"}
    if service == "payments":
        result.update(PAYMENTS_DB_URL=database["url"], PAYMENTS_DB_USER=database["runtime_user"], PAYMENTS_SQL_INIT_MODE="never")
    else:
        result.update(DB_URL=database["url"], DB_USER=database["user"])
    return result


def prepare_carts(base, secrets):
    seller = MarketProbe(base, secrets_dir=secrets)
    seller.login("seller", (secrets / "seller_password").read_text().strip())
    products = seller.post("/catalog/demo", seller.read("/catalog"))
    product = min(products.products)
    fixtures = []
    for _ in range(BACKLOG):
        buyer = MarketProbe(base)
        cart = buyer.post("/cart/items", buyer.read("/items"), id=product, action="PLUS")
        assert cart.checkout_key and cart.amount_minor
        fixtures.append({"cookie": "; ".join(cookie.name + "=" + cookie.value for cookie in buyer.cookies),
                         "form": {"_csrf": cart.token, "checkoutKey": cart.checkout_key, "amountMinor": cart.amount_minor}})
    return product, fixtures


def k6(directory, base, product, phase):
    destination = directory / (phase + "-summary.json")
    environment = {**os.environ, "BASE_URL": base, "PRODUCT_ID": str(product), "PHASE": phase,
                   "FIXTURES": str(directory / "fixtures.json"), "K6_NO_USAGE_REPORT": "true"}
    with (directory / (phase + "-k6.log")).open("w") as log:
        subprocess.run(["k6", "run", "--quiet", "--summary-export", str(destination),
                        str(PROJECT / "scripts/payment-load.js")], env=environment, check=True,
                       stdout=log, stderr=subprocess.STDOUT, timeout=100)
    return json.loads(destination.read_text())["metrics"]


def reconcile(databases, payments, secrets):
    store = sql(databases["storefront"], "SELECT i.id, i.payer_id, i.amount_minor, o.payment_id FROM checkout_intents i "
                "JOIN purchase_orders o ON o.id=i.order_id WHERE i.status='CONFIRMED' AND o.status='PAID'")
    assert len(store) == BACKLOG + FOREGROUND and len({row["payment_id"] for row in store}) == len(store)
    assert sql(databases["storefront"], "SELECT count(*) AS n FROM checkout_intents WHERE status <> 'CONFIRMED'")[0]["n"] == 0
    assert sql(databases["storefront"], "SELECT count(*) AS n FROM cart_items")[0]["n"] == 0
    assert sql(databases["storefront"], "SELECT count(*) AS n FROM analytics_outbox")[0]["n"] == len(store)
    decisions = sql(databases["payments"], "SELECT payment_id, reference_id, account_id, amount_minor, status FROM payment_decisions")
    expected = {(row["payment_id"], row["id"], row["payer_id"], row["amount_minor"], "SUCCEEDED") for row in store}
    assert {tuple(row.values()) for row in decisions} == expected, "Every paid order must match exactly one successful debit"
    journal = sql(databases["payments"], "SELECT count(*) AS n, sum(positive_minor) AS total, "
                  "sum(positive_minor + negative_minor) AS net FROM payment_journal WHERE kind='DEBIT'")[0]
    assert journal == {"n": len(store), "total": sum(row["amount_minor"] for row in store), "net": 0}
    wallets = "SELECT account_id, opening_balance_minor, balance_minor FROM payment_wallets ORDER BY account_id"
    before = sql(databases["payments"], wallets)
    charged = {row["payer_id"]: row["amount_minor"] for row in store}
    assert len(charged) == len(store), "Each isolated guest must retain its own payment account"
    assert all(row["opening_balance_minor"] - row["balance_minor"] == charged[row["account_id"]] for row in before)
    token = (secrets / "payments_service_token").read_text().strip()

    def replay(row):
        request = Request(payments + "/api/v1/payments", data=json.dumps({"accountId": row["payer_id"],
                          "referenceId": row["id"], "amountMinor": row["amount_minor"], "currency": "RUB"}).encode(),
                          headers={"Authorization": "Bearer " + token, "Content-Type": "application/json", "Idempotency-Key": row["id"]})
        with urlopen(request, timeout=10) as response:
            assert response.status == 200 and json.load(response)["paymentId"] == row["payment_id"]

    with ThreadPoolExecutor(max_workers=4) as workers:
        list(workers.map(replay, store))
    assert sql(databases["payments"], wallets) == before, "Payment replay must preserve every wallet balance"
    assert len(sql(databases["payments"], "SELECT payment_id FROM payment_decisions")) == len(store)
    assert sql(databases["payments"], "SELECT count(*) AS n FROM payment_journal WHERE kind='DEBIT'")[0]["n"] == len(store)
    return {"paid_orders": len(store), "charged_minor": journal["total"], "replayed_payments": len(store)}


def main():
    if not shutil.which("k6"):
        raise SystemExit("Install k6 before running this verification")
    with run_directory("payment-load") as directory:
        secrets = initialize(directory / "secrets")
        with postgres(secrets) as databases, redis(secrets) as cache:
            jar = lambda service: PROJECT / service / "build/libs" / (service + ".jar")
            with application("payments", jar("payments"), directory, secrets,
                             environment("payments", free_port(), directory, secrets, databases["payments"])) as payments:
                with payment_proxy(payments) as (proxy, state, lock):
                    settings = environment("storefront", free_port(), directory, secrets, databases["storefront"])
                    settings.update(STORE_PAYMENTS_URL=proxy, SPRING_DATA_REDIS_HOST=cache["host"],
                                    SPRING_DATA_REDIS_PORT=cache["port"], SPRING_DATA_REDIS_USERNAME="storefront",
                                    STORE_CACHE_NAMESPACE="sprint07-load", STORE_SECRETS_LOCATION=str(directory / "storefront-config") + "/")
                    with application("storefront", jar("storefront"), directory, secrets, settings) as base:
                        product, fixtures = prepare_carts(base, secrets)
                        fixtures_path = directory / "fixtures.json"
                        fixtures_path.write_text(json.dumps(fixtures))
                        fixtures_path.chmod(0o600)
                        k6(directory, base, product, "queue")
                        pending = sql(databases["storefront"], "SELECT id FROM checkout_intents WHERE status='PREPARED' ORDER BY created_at, id")
                        assert len(pending) == BACKLOG and len(pending) > 32, "The backlog must exceed a complete recovery batch"
                        with lock:
                            state.update(recovery={row["id"] for row in pending}, blocked={row["id"] for row in pending[:BLOCKED]}, outage=False)
                        started = time.monotonic()

                        def recovery_started():
                            with lock:
                                return state["forwarded"] > 0

                        wait_for(recovery_started, "Recovery after payment outage", timeout=70)
                        metrics = k6(directory, base, product, "foreground")
                        healthy = "SELECT count(*) AS n FROM checkout_intents WHERE status='CONFIRMED'"
                        wait_for(lambda: sql(databases["storefront"], healthy)[0]["n"] == BACKLOG - BLOCKED + FOREGROUND,
                                 "Healthy intents behind failing payments", timeout=90)
                        partial_seconds = time.monotonic() - started
                        remaining = sql(databases["storefront"], "SELECT id, last_attempt_at FROM checkout_intents WHERE status='PREPARED'")
                        with lock:
                            assert {row["id"] for row in remaining} == state["blocked"] and all(row["last_attempt_at"] for row in remaining)
                            state["blocked"] = set()
                        wait_for(lambda: sql(databases["storefront"], healthy)[0]["n"] == BACKLOG + FOREGROUND,
                                 "Remaining recovered payments", timeout=90)
                        with lock:
                            peak = state["peak"]
                            recovery_forwarded = state["forwarded"]
                            foreground_dispatches = {key: count for key, count in state["requests"].items() if key not in state["recovery"]}
                        assert peak == 2, "Recovery must run concurrently within its two-request budget"
                        assert recovery_forwarded == BACKLOG, "Each recovered intent must dispatch exactly one successful payment request"
                        assert len(foreground_dispatches) == FOREGROUND and set(foreground_dispatches.values()) == {1}, \
                            "Recovery must not duplicate foreground payment requests"
                        report = {"backlog": BACKLOG, "temporarily_failing": BLOCKED, "healthy_drain_seconds": round(partial_seconds, 2),
                                  "complete_drain_seconds": round(time.monotonic() - started, 2), "peak_recovery_http": peak,
                                  "recovery_forwarded": recovery_forwarded, "foreground_payment_requests": sum(foreground_dispatches.values()),
                                  "jvm_heap_limit_mib": 192, "jvm_direct_limit_mib": 48,
                                  "catalog_ms": metrics["catalog_ms"], "purchase_ms": metrics["purchase_ms"],
                                  **reconcile(databases, payments, secrets)}
                        (directory / "report.json").write_text(json.dumps(report, indent=2))
                        print(json.dumps(report, indent=2))
                        print("Finite local load only: 200 ms injected payment latency, native JAR heap/direct limits; not a production SLO")


if __name__ == "__main__":
    main()
