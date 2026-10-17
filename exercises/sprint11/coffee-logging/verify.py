#!/usr/bin/env python3
"""Проверяет настоящий путь от консольного приложения до файлов и Elasticsearch."""
import json
import os
import re
import subprocess
import uuid
from urllib.error import HTTPError

from bootstrap import ROOT, request, wait_for

COMPOSE = ["docker", "compose", "-p", "java-sprint11-coffee", "-f", "compose.yml"]
FIELDS = {"time", "coffeeSort", "coffeeType", "volume", "isNight"}


def compose(*args, mode="files", replay="main"):
    subprocess.run([*COMPOSE, *args], cwd=ROOT, check=True, timeout=180,
                   env={**os.environ, "OUTPUT_MODE": mode, "REPLAY_ID": replay})


def output_lines():
    return {name: (ROOT / f"target/output/{name}").read_text().splitlines()
            if (ROOT / f"target/output/{name}").exists() else []
            for name in ("coffee-processed.txt", "coffee-errors.txt")}


def check_document(document, fields=FIELDS):
    assert set(document) == fields, f"Unexpected output fields: {set(document)}"
    assert type(document["isNight"]) is bool, "isNight must be a JSON boolean"
    assert re.fullmatch(r"\d{2}:\d{2}:\d{2}\.\d{3}", document["time"])
    assert document["isNight"] == (int(document["time"][:2]) >= 22 or int(document["time"][:2]) < 8)
    assert document["coffeeType"] in {"Эспрессо", "Американо", "Капучино"}
    assert document["coffeeSort"] in {"Бразилия", "Эфиопия"}
    assert document["volume"] in {"0,2", "0,3", "0,4", "invalid"}


def search():
    try:
        result = request("/coffee-logs-*/_search?size=1000&track_total_hits=true")
    except HTTPError as error:
        if error.code == 404:
            return []
        raise
    assert result["hits"]["total"]["relation"] == "eq"
    assert result["hits"]["total"]["value"] <= 1000, "Use a fresh stack or paginate a larger dataset"
    return result["hits"]["hits"]


def verify():
    expected_count = len(search()) + 7
    compose("up", "-d", "logstash", "filebeat")
    before = output_lines()
    program = ROOT / "build/install/coffee-logging/bin/coffee-logging"
    result = subprocess.run([str(program)], input="reader\n0,3\nreader\nprivate-invalid-input\n",
                            text=True, capture_output=True, timeout=30, cwd=ROOT)
    assert result.returncode == 0, "Console application failed"
    invalid = subprocess.run([str(program)], input="bad username\n0,3\n", text=True,
                             capture_output=True, timeout=30, cwd=ROOT)
    assert invalid.returncode != 0, "Unsafe username accepted"
    log = ROOT / "target/logs/coffee.log"
    raw = log.read_text()
    assert "private-invalid-input" not in raw and "bad username" not in raw
    with log.open("a") as output:
        output.write("\n".join([
            "07:59:59.999 INFO : alice Бразилия, Эспрессо: 0,2",
            "08:00:00.000 INFO : bob Эфиопия, Американо: 0,3",
            "21:59:59.999 INFO : carol Бразилия, Капучино: 0,4",
            "22:00:00.000 INFO : dave Эфиопия, Эспрессо: 0,2",
            "23:00:00.000 ERROR: erin Бразилия, Американо: invalid",
            "23:00:00.001 INFO : test Бразилия, Эспрессо: 0,2",
            "23:00:00.002 WARN : ignored Бразилия, Эспрессо: 0,2",
            "malformed private-user input",
        ]) + "\n")

    def files_ready():
        lines = output_lines()
        return lines if sum(len(lines[n]) - len(before[n]) for n in lines) >= 7 else None

    lines = wait_for(files_ready, "Filebeat → Kafka → Logstash file output")
    new = {name: [json.loads(line) for line in rows[len(before[name]):]] for name, rows in lines.items()}
    assert len(new["coffee-processed.txt"]) == 5 and len(new["coffee-errors.txt"]) == 2
    for name, documents in new.items():
        for document in documents:
            check_document(document)
            assert (document["volume"] == "invalid") == (name == "coffee-errors.txt")
    print("Console validation, routing, redaction, boolean and night boundaries passed")

    compose("stop", "logstash")
    compose("up", "-d", "logstash", mode="elasticsearch")
    hits = wait_for(lambda: (rows if len(rows := search()) >= expected_count else None), "Elasticsearch documents")
    assert len(hits) == expected_count, "Unexpected records in the isolated exercise stack"
    for hit in hits:
        check_document(hit["_source"], FIELDS | {"@timestamp", "log_level"})
        assert re.fullmatch(r"coffee-logs-0-\d+", hit["_id"]), "Missing transport identity"
        assert re.fullmatch(r"coffee-logs-\d{4}\.\d{2}\.\d{2}", hit["_index"])
    ids = {hit["_id"] for hit in hits}
    mapping = request("/coffee-logs-*/_mapping")
    assert all(index["mappings"]["properties"]["isNight"]["type"] == "boolean" for index in mapping.values())
    policy = request("/_ilm/policy/coffee-logs-policy")["coffee-logs-policy"]["policy"]
    assert policy["phases"]["delete"]["min_age"] == "7d"
    assert all(index["managed"] for index in request("/coffee-logs-*/_ilm/explain")["indices"].values())
    assert request("/coffee-logs-*/_count", user="study")["count"] == len(ids)
    for user, path, method in [("coffee_writer", "/coffee-logs-*/_search", "GET"),
                               ("study", "/coffee-logs-forbidden", "PUT")]:
        try:
            request(path, method=method, user=user)
            raise AssertionError(f"Unexpected permission granted to {user}")
        except HTTPError as error:
            assert error.code == 403
    print("Strict mapping, daily indexes, retention and separate read/write permissions passed")

    compose("stop", "logstash")
    replay = "verify-" + uuid.uuid4().hex[:12]
    compose("up", "-d", "logstash", mode="elasticsearch", replay=replay)

    def replay_finished():
        result = subprocess.run([*COMPOSE, "exec", "-T", "kafka", "/opt/kafka/bin/kafka-consumer-groups.sh",
                                 "--bootstrap-server", "kafka:9092", "--describe", "--group", f"coffee-elasticsearch-{replay}"],
                                cwd=ROOT, text=True, capture_output=True, timeout=15)
        rows = [row.split() for row in result.stdout.splitlines() if "coffee-logs" in row]
        return rows and all(len(row) >= 6 and row[5] == "0" for row in rows)

    wait_for(replay_finished, "Kafka replay committed offsets")
    # Нулевой lag означает запись в PQ, поэтому отдельно ждём завершения output.
    def outputs_finished():
        pipeline = subprocess.run([*COMPOSE, "exec", "-T", "logstash", "curl", "-fsS",
                                   "http://localhost:9600/_node/stats/pipelines"],
                                  cwd=ROOT, text=True, capture_output=True, timeout=15)
        if pipeline.returncode:
            return False
        main = json.loads(pipeline.stdout).get("pipelines", {}).get("main", {})
        return main.get("events", {}).get("out", 0) >= len(ids) and main.get("queue", {}).get("events_count") == 0

    wait_for(outputs_finished, "Logstash replay output acknowledgements")
    assert {hit["_id"] for hit in search()} == ids, "Kafka replay duplicated Elasticsearch documents"
    print("Same Kafka records replayed without new document IDs; business deduplication is not implied")


if __name__ == "__main__":
    verify()
