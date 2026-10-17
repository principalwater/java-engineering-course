#!/usr/bin/env python3
"""Проверяет HTTP/JPA, реальные метрики и доставку firing/resolved через SMTP."""
import argparse
import base64
import json
from pathlib import Path
import re
import time
from urllib.error import URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from load import request, run

ROOT = Path(__file__).resolve().parent
PROMETHEUS = "http://127.0.0.1:19090"
GRAFANA = "http://127.0.0.1:19300"
MAILPIT = "http://127.0.0.1:19325"


def get_json(url, headers=None):
    with urlopen(Request(url, headers=headers or {}), timeout=10) as response:
        return json.load(response)


def await_value(operation, accepted, timeout=90):
    deadline = time.monotonic() + timeout
    while True:
        try:
            value = operation()
            if accepted(value):
                return value
        except (URLError, TimeoutError):
            pass
        if time.monotonic() >= deadline:
            raise AssertionError("Timed out waiting for required monitoring evidence")
        time.sleep(1)


def query(expression):
    response = get_json(PROMETHEUS + "/api/v1/query?" + urlencode({"query": expression}))
    assert response["status"] == "success", response
    return response["data"]["result"]


def messages():
    return get_json(MAILPIT + "/api/v1/messages")["messages"]


def main(alerts):
    await_value(lambda: request("/actuator/health/readiness"), lambda result: result[0] == 200)
    status, body = request("/orders")
    assert status == 200
    before = len(json.loads(body))
    for kind in ("BROWSER", "MOBILE", "OTHER"):
        status, body = request("/orders", {"username": "study-user", "orderType": kind})
        saved = json.loads(body)
        assert status == 201 and saved["id"] > 0 and saved["orderType"] == kind
    for invalid in ({"username": "", "orderType": "OTHER"},
                    {"username": "x" * 65, "orderType": "OTHER"},
                    {"username": "study-user", "orderType": "UNKNOWN"}):
        assert request("/orders", invalid)[0] == 400
    assert len(json.loads(request("/orders")[1])) == before + 3
    status, body = request("/actuator/prometheus")
    exposition = body.decode()
    assert status == 200
    assert len(re.findall(r'^orders_total\{[^\n]+type="(?:BROWSER|MOBILE|OTHER)"', exposition, re.M)) == 3
    counts = re.findall(r'^orders_total\{[^}]*\} ([\d.eE+-]+)', exposition, re.M)
    assert sum(map(float, counts)) == before + 3
    assert 'http_server_requests_seconds_bucket{' in exposition
    assert 'hikaricp_connections_active{' in exposition
    await_value(lambda: query('up{job="orders"}'), lambda result: result and result[0]["value"][1] == "1")
    password = (ROOT / "target/lab.env").read_text().strip().split("=", 1)[1]
    headers = {"Authorization": "Basic " + base64.b64encode(f"study:{password}".encode()).decode()}
    await_value(lambda: get_json(GRAFANA + "/api/health"), lambda result: result.get("database") == "ok")
    dashboard = get_json(GRAFANA + "/api/dashboards/uid/orders-monitoring", headers)
    assert dashboard["meta"]["provisioned"]
    assert get_json(GRAFANA + "/api/datasources/uid/prometheus/health", headers)["status"] == "OK"
    print("HTTP validation, committed JPA rows, bounded metric labels, histogram, scrape and Grafana datasource: passed", flush=True)
    if alerts:
        print("Normal load: 15 orders/s for 70 seconds", flush=True)
        print(run(70, 15, 35), flush=True)
        assert float(query('sum(rate(orders_total{job="orders"}[1m]))')[0]["value"][1]) >= 10
        assert not get_json(PROMETHEUS + "/api/v1/alerts")["data"]["alerts"]
        baseline = {message["ID"] for message in messages()}
        print("Low load: 7 orders/s for 130 seconds; waiting through the 1m window and for=1m", flush=True)
        print(run(130, 7, 25), flush=True)
        firing = await_value(messages, lambda rows: any(row["ID"] not in baseline and
                            row["Subject"] == "FIRING: LowOrderRate" for row in rows), timeout=30)
        assert any(item["labels"]["alertname"] == "LowOrderRate" and item["state"] == "firing"
                   for item in get_json(PROMETHEUS + "/api/v1/alerts")["data"]["alerts"])
        firing_ids = {message["ID"] for message in firing}
        print("Recovery load: 15 orders/s for 70 seconds", flush=True)
        print(run(70, 15, 35), flush=True)
        await_value(messages, lambda rows: any(row["ID"] not in firing_ids and
                    row["Subject"] == "RESOLVED: LowOrderRate" for row in rows), timeout=30)
        assert not get_json(PROMETHEUS + "/api/v1/alerts")["data"]["alerts"]
        print("Actual LowOrderRate Firing and Resolved emails captured in local Mailpit: passed", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--alerts", action="store_true", help="Include the full real-time SMTP scenario")
    main(parser.parse_args().alerts)
