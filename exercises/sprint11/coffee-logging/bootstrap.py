#!/usr/bin/env python3
"""Настраивает роли, template и срок хранения только учебного стенда."""
import base64
import json
from pathlib import Path
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parent
CREDENTIALS = json.loads((ROOT / "target/credentials.json").read_text())


def request(path, data=None, method=None, user="elastic", kibana=False, content_type="application/json", raw=False):
    base = "http://127.0.0.1:19601" if kibana else "http://127.0.0.1:19200"
    authorization = base64.b64encode(f"{user}:{CREDENTIALS[user]}".encode()).decode()
    headers = {"Authorization": f"Basic {authorization}", "kbn-xsrf": "coffee-lab"}
    body = data if isinstance(data, bytes) else None if data is None else json.dumps(data).encode()
    if body is not None:
        headers["Content-Type"] = content_type
    with urlopen(Request(base + path, data=body, headers=headers, method=method), timeout=10) as response:
        return response.read() if raw else json.load(response)


def wait_for(probe, description, timeout=180):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            result = probe()
            if result:
                return result
        except (HTTPError, URLError, TimeoutError, ConnectionError):
            pass
        time.sleep(1)
    raise AssertionError(f"Timed out waiting for {description}")


def bootstrap():
    wait_for(lambda: request("/_cluster/health")["status"] in ("yellow", "green"), "Elasticsearch")
    request("/_security/user/kibana_system/_password", {"password": CREDENTIALS["kibana_system"]}, "POST")
    request("/_ilm/policy/coffee-logs-policy", {"policy": {"phases": {
        "hot": {"actions": {}}, "delete": {"min_age": "7d", "actions": {"delete": {}}}
    }}}, "PUT")
    properties = {name: {"type": "keyword"} for name in ("time", "coffeeSort", "coffeeType", "volume", "log_level")}
    properties.update({"@timestamp": {"type": "date"}, "isNight": {"type": "boolean"}})
    request("/_index_template/coffee-logs-template", {
        "index_patterns": ["coffee-logs-*"], "priority": 200,
        "template": {"settings": {"number_of_shards": 1, "number_of_replicas": 0,
                                   "index.lifecycle.name": "coffee-logs-policy"},
                     "mappings": {"dynamic": "strict", "properties": properties}},
    }, "PUT")
    request("/_ingest/pipeline/coffee-ingested", {"processors": [
        {"set": {"field": "@timestamp", "value": "{{{_ingest.timestamp}}}"}}
    ]}, "PUT")
    request("/_security/role/coffee_writer", {
        "cluster": ["monitor"],
        "indices": [{"names": ["coffee-logs-*"], "privileges": ["create_index", "index", "view_index_metadata"]}],
    }, "PUT")
    request("/_security/user/coffee_writer", {"password": CREDENTIALS["coffee_writer"], "roles": ["coffee_writer"]}, "PUT")
    # Полная настройка интерфейса нужна только для учебных визуализаций; доступ к другим данным не выдаётся.
    request("/_security/role/coffee_reader", {
        "indices": [{"names": ["coffee-logs-*"], "privileges": ["read", "view_index_metadata"]}],
    }, "PUT")
    request("/_security/user/study", {"password": CREDENTIALS["study"], "roles": ["coffee_reader", "kibana_admin"]}, "PUT")
    print("Coffee mappings, ingestion pipeline, seven-day retention and users ready")


if __name__ == "__main__":
    bootstrap()
