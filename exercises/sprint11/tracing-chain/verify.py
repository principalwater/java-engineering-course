#!/usr/bin/env python3
"""Проверка распространения W3C-контекста через настоящий HTTP и Zipkin."""
import json
import os
from pathlib import Path
import socket
import subprocess
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from uuid import uuid4

ROOT = Path(__file__).resolve().parent
PORTS = (19301, 19302, 19303)
ZIPKIN = "http://127.0.0.1:19411"


def get(url, headers=None):
    try:
        with urlopen(Request(url, headers=headers or {}), timeout=8) as response:
            return response.status, response.read()
    except HTTPError as error:
        with error:
            return error.code, error.read()


def await_result(operation, accepted, timeout=60):
    deadline = time.monotonic() + timeout
    while True:
        try:
            value = operation()
            if accepted(value):
                return value
        except (URLError, TimeoutError, ConnectionError):
            pass
        if time.monotonic() >= deadline:
            raise AssertionError("Timed out waiting for the required state")
        time.sleep(0.2)


def check_trace(trace_id):
    status, body = get(f"http://127.0.0.1:{PORTS[0]}/chain", {
        "traceparent": f"00-{trace_id}-0123456789abcdef-01",
    })
    assert status == 500 and body == b"Chain dependency unavailable"
    _, body = await_result(
        lambda: get(f"{ZIPKIN}/api/v2/trace/{trace_id}"),
        lambda result: result[0] == 200 and len(json.loads(result[1])) == 6,
    )
    spans = json.loads(body)
    assert {span["traceId"] for span in spans} == {trace_id}
    by_id = {span["id"]: span for span in spans}
    assert len(by_id) == 6
    servers = {span["localEndpoint"]["serviceName"]: span
               for span in spans if span.get("kind") == "SERVER"}
    assert set(servers) == {f"chain-{port}" for port in PORTS}
    for port, span in servers.items():
        assert span["tags"]["status"] == "500", (port, span)
    assert servers[f"chain-{PORTS[0]}"]["parentId"] == "0123456789abcdef"
    for previous, following in zip(PORTS, PORTS[1:]):
        parent = by_id[servers[f"chain-{following}"]["parentId"]]
        assert parent["kind"] == "CLIENT"
        assert parent["parentId"] == servers[f"chain-{previous}"]["id"]
    last = [span for span in spans if span.get("kind") == "CLIENT"
            and span["parentId"] == servers[f"chain-{PORTS[-1]}"]["id"]]
    assert len(last) == 1 and last[0]["tags"].get("error")
    return set(by_id)


def main():
    await_result(lambda: get(ZIPKIN + "/api/v2/services"), lambda result: result[0] == 200)
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in os.environ else "java"
    output = ROOT / "build/verification"
    output.mkdir(parents=True, exist_ok=True)
    processes, logs = [], []
    try:
        for port in (*PORTS, PORTS[-1] + 1):
            # До запуска убеждаемся, что порт не принадлежит другому процессу.
            with socket.socket() as probe:
                probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                probe.bind(("127.0.0.1", port))
        for port in PORTS:
            log = (output / f"chain-{port}.log").open("w")
            logs.append(log)
            processes.append(subprocess.Popen([
                java, "-Xmx160m", "-jar", str(ROOT / "build/libs/tracing-chain.jar"),
                f"--server.port={port}",
            ], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT))
        for port in PORTS:
            await_result(lambda: get(f"http://127.0.0.1:{port}/actuator/health/readiness"),
                         lambda result: result[0] == 200)
        first, second = uuid4().hex, uuid4().hex
        assert check_trace(first).isdisjoint(check_trace(second))
        print("HTTP 500, 3 server spans, 3 client spans, W3C parent links and context isolation: passed")
        print(f"Trace: {ZIPKIN}/zipkin/traces/{second}")
    finally:
        for process in processes:
            process.terminate()
        for process in processes:
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        for log in logs:
            log.close()


if __name__ == "__main__":
    main()
