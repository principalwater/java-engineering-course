"""Границы живого verifier: данные API, связность трасс и безопасная диагностика."""

import copy
from contextlib import contextmanager, redirect_stderr, redirect_stdout
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import io
import json
from pathlib import Path
import threading
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


specification = importlib.util.spec_from_file_location("verify_observability", Path(__file__).with_name("verify-observability.py"))
observability = importlib.util.module_from_spec(specification)
specification.loader.exec_module(observability)


@contextmanager
def api(responder, *, with_headers=False):
    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            self.respond(body)

        def do_GET(self):
            self.respond(None)

        def respond(self, body):
            status, data = responder(self.path, body, self.headers) if with_headers else responder(self.path, body)
            encoded = json.dumps(data).encode()
            self.send_response(status)
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)

        def log_message(self, *_):
            pass

    with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            yield server.server_port
        finally:
            server.shutdown()
            worker.join()


def dashboard(expression, uid="bank-prometheus"):
    datasource = {"uid": uid, "type": "elasticsearch" if uid == "bank-logs" else "prometheus"}
    target = {"query" if uid == "bank-logs" else "expr": expression, "datasource": datasource}
    return {"panels": [{"id": 1, "title": "Fixture chart", "targets": [target]}]}


def frame(value, field_type="number"):
    return {"schema": {"fields": [{"type": "time"}, {"type": field_type}]},
            "data": {"values": [[1234567890000], [value]]}}


class DashboardApiTest(unittest.TestCase):
    def test_chart_requests_preserve_the_published_query_format_and_legend(self):
        # format=table меняет результат во frontend Grafana, даже когда backend вернул те же ряды.
        document = dashboard('sum by (topic) (metric{topic=~"$topic"})')
        document["templating"] = {"list": [{"name": "topic", "includeAll": True, "allValue": ".+"}]}
        document["panels"][0]["targets"][0].update(
            format="table", legendFormat="{{topic}}", instant=True, range=False, interval="30s", refId="A")
        requests = []
        def respond(path, body):
            requests.append(body["queries"][0])
            return 200, {"results": {body["queries"][0]["refId"]: {"frames": [frame(0)]}}}
        with api(respond) as port:
            outcome = observability.query_dashboard(port, {}, document, False)
        self.assertEqual(1, outcome["answered"])
        self.assertEqual(1, len(requests))
        self.assertEqual("table", requests[0].get("format"))
        self.assertEqual("{{topic}}", requests[0].get("legendFormat"))
        self.assertEqual("30s", requests[0].get("interval"))
        self.assertTrue(requests[0]["instant"])
        self.assertFalse(requests[0]["range"])
        self.assertEqual('sum by (topic) (metric{topic=~".+"})', requests[0]["expr"])
        self.assertEqual("q0", requests[0]["refId"])
        self.assertEqual(500, requests[0]["maxDataPoints"])

    def test_missing_dashboards_cannot_pass_an_empty_chart_run(self):
        sources = observability.load_yaml(observability.PROJECT / "grafana/datasources.yaml")["datasources"]
        def respond(path, body, headers):
            if path == "/api/health":
                return 200, {"database": "ok"}
            if not headers.get("Authorization"):
                return 401, {}
            if path == "/api/user":
                return 200, {"login": "bank11-operator", "isGrafanaAdmin": False}
            if path == "/api/user/orgs":
                return 200, [{"role": "Viewer"}]
            if path == "/api/dashboards/db":
                return 403, {}
            if path == "/api/datasources":
                return 200, [{**source, "readOnly": True} for source in sources]
            if path.endswith("/health"):
                return 200, {"status": "OK"}
            return 200, []
        report = observability.Report()
        with api(respond, with_headers=True) as port, redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            stack = SimpleNamespace(project=observability.PROJECT, analytics=False,
                                    password=lambda _: "fixture-password",
                                    forwards=SimpleNamespace(port=lambda _: port))
            observability.check_grafana(stack, report)
        self.assertTrue(any(check["name"] == "grafana charts" and check["status"] == "FAIL"
                            for check in report.checks))

    def test_timestamp_and_null_are_empty_but_zero_is_real_data(self):
        # Раньше одна временная колонка скрывала пустую обязательную метрику.
        for value, answered in ((None, 0), (float("nan"), 0), (0, 1), (4.5, 1)):
            with self.subTest(value=value), api(lambda path, body: (200, {
                    "results": {body["queries"][0]["refId"]: {"frames": [frame(value)]}}})) as port:
                outcome = observability.query_dashboard(port, {}, dashboard("sum(jvm_memory_used_bytes)"), False)
            self.assertEqual(answered, outcome["answered"])
            self.assertEqual(1 - answered, len(outcome["empty_core"]))

    def test_missing_query_result_is_error_and_error_body_stays_private(self):
        for result in ({}, {"q0": {"error": "Bearer private-backend-token", "status": 400}}):
            with self.subTest(result=result), api(lambda path, body: (400, {"results": result})) as port:
                outcome = observability.query_dashboard(port, {}, dashboard("up"), False)
            self.assertEqual(1, len(outcome["errors"]))
            self.assertNotIn("private-backend-token", json.dumps(outcome))

    def test_optional_and_zero_event_queries_still_execute_without_requiring_data(self):
        cases = (("min(up{job=\"clickhouse\"})", "bank-prometheus", "optional"),
                 ("level:ERROR", "bank-logs", "zero_state"),
                 ('sum(rate(http_server_requests_seconds_count{status_code=~"5.."}[5m]))', "bank-prometheus", "zero_state"),
                 ("sum(rate(alertmanager_notifications_total[5m]))", "bank-prometheus", "zero_state"))
        for expression, uid, category in cases:
            requests = []
            def respond(path, body):
                requests.append((path, body))
                return 200, {"results": {body["queries"][0]["refId"]: {"frames": []}}}
            with self.subTest(expression=expression), api(respond) as port:
                outcome = observability.query_dashboard(port, {}, dashboard(expression, uid), False)
            self.assertEqual(1, len(requests))
            self.assertEqual(["Fixture chart"], outcome[category])
            self.assertEqual([], outcome["empty_core"])


def trace_fixture():
    trace = "1" * 32
    def span(index, name, service, parent=None, kind=None, tags=None):
        item = {"traceId": trace, "id": f"{index:016x}", "name": name,
                "localEndpoint": {"serviceName": service}, "tags": tags or {}}
        if parent is not None:
            item["parentId"] = f"{parent:016x}"
        if kind:
            item["kind"] = kind
        return item
    return [span(1, "http get", "cash", kind="SERVER", tags={"http.method": "GET"}),
            span(2, "query", "cash", 1, tags={"jdbc.query": "select"}),
            span(3, "notifications publish", "cash", 1, "PRODUCER", {"messaging.system": "kafka"}),
            span(4, "notifications receive", "notifications", 3, "CONSUMER", {"messaging.system": "kafka"})]


class TraceAndLogTest(unittest.TestCase):
    def test_requires_connected_trace_and_matching_identifiers(self):
        good = trace_fixture()
        facts, problems = observability.analyze_traces([good], {"cash", "notifications"}, ())
        self.assertEqual([], problems)
        self.assertEqual(1, facts["chains"])
        cycle = copy.deepcopy(good)
        cycle[2]["parentId"] = cycle[3]["id"]
        mixed = copy.deepcopy(good)
        mixed[3]["traceId"] = "2" * 32
        invalid = copy.deepcopy(good)
        invalid[3]["id"] = "0" * 16
        for broken in (cycle, mixed, invalid):
            with self.subTest(broken=broken):
                facts, problems = observability.analyze_traces([broken], {"cash", "notifications"}, ())
            self.assertEqual(0, facts["chains"])
            self.assertTrue(problems)

    def test_trace_url_and_nested_log_secrets_are_reported_without_values(self):
        secret = "private-fixture-password"
        trace = trace_fixture()
        trace[0]["tags"]["http.url"] = "https://bank.invalid/account?code=" + secret
        facts, _ = observability.analyze_traces([trace], {"cash", "notifications"}, (secret,))
        self.assertTrue(facts["leaks"])
        self.assertNotIn(secret, json.dumps(facts))
        document = {"service": "cash", "level": "WARN", "event": "Cash rejected", "logger_name": "Cash",
                    "@timestamp": "2026-10-21T10:00:00Z", "traceId": trace[0]["traceId"], "spanId": trace[0]["id"],
                    "extra": {"detail": secret}}
        _, defects, gaps = observability.analyze_logs([document], {"cash"}, (secret,))
        self.assertTrue(defects)
        self.assertEqual([], gaps)
        self.assertNotIn(secret, json.dumps(defects))

    def test_log_correlation_requires_the_actual_logged_span(self):
        trace = trace_fixture()
        document = {"traceId": trace[0]["traceId"], "spanId": trace[1]["id"]}
        for returned, matches in (([], 0), (trace, 1), ([{**trace[0], "traceId": "2" * 32}], 0)):
            with self.subTest(returned=returned), api(lambda path, body: (200, returned)) as port:
                self.assertEqual(matches, observability.correlated_logs(port, [document]))


class RuntimeBoundaryTest(unittest.TestCase):
    def test_secret_scan_requires_private_regular_files_inside_the_supplied_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            secret = root / "password"
            secret.write_text("private-fixture-password\n", encoding="utf-8")
            secret.chmod(0o600)
            self.assertEqual(["private-fixture-password"], observability.secret_needles(root, ["password"]))
            (root / "link").symlink_to(secret)
            for name in ("missing", "link", "../password"):
                with self.subTest(name=name), self.assertRaises(observability.VerificationError):
                    observability.secret_needles(root, [name])
            secret.chmod(0o644)
            with self.assertRaises(observability.VerificationError):
                observability.secret_needles(root, ["password"])

    def test_worker_endpoint_and_optional_services_are_checked_independently(self):
        services = observability.expected_services({"apps": {"cash": {}, "notifications": {"web": False},
                                                            "analytics": {"optional": True}}}, False)
        targets = [{"labels": {"job": job, "service": name}, "health": "up",
                    "scrapeUrl": f"http://{name}:{port}/actuator/prometheus"}
                   for name, job, port in (("cash", "bank-web", 8080), ("notifications", "bank-worker", 9400))]
        self.assertEqual([], observability.target_problems(targets, ["bank-web", "bank-worker", "clickhouse"], services, False))
        targets[1]["scrapeUrl"] = "http://notifications:8080/actuator/prometheus"
        self.assertTrue(observability.target_problems(targets, ["bank-web", "bank-worker"], services, False))

    def test_expired_shared_budget_never_starts_another_poll(self):
        stack = observability.Stack(Path("."), {}, Path("."), {}, None, None)
        stack.deadline = -1
        with patch.object(observability.time, "sleep", side_effect=AssertionError("Expired budget attempted a sleep")) as sleep, \
                patch.object(observability.time, "monotonic", side_effect=(1, 2, 3)):
            with self.assertRaises(observability.VerificationError):
                stack.wait("Fixture", lambda: ["missing"])
        sleep.assert_not_called()

    def test_report_does_not_echo_arbitrary_external_exceptions_or_secret_values(self):
        report = observability.Report(("fixture-password",))
        with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()) as errors:
            with report.guarded("backend"):
                raise ValueError("Bearer private-external-token")
            report.failed("known", "fixture-password")
        self.assertNotIn("private-external-token", errors.getvalue())
        self.assertNotIn("fixture-password", json.dumps(report.document("fixture")))


if __name__ == "__main__":
    unittest.main()
