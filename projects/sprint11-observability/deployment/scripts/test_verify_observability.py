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
        sources = observability.load_yaml(observability.PROJECT / "observability/grafana/datasources.yaml")["datasources"]
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
    return [span(1, "http post", "front", kind="SERVER", tags={"http.method": "POST"}),
            span(2, "http post", "front", 1, "CLIENT", {"http.method": "POST"}),
            span(3, "http post", "gateway", 2, "SERVER", {"http.method": "POST"}),
            span(4, "http post", "gateway", 3, "CLIENT", {"http.method": "POST"}),
            span(5, "http post", "cash", 4, "SERVER", {"http.method": "POST"}),
            span(6, "http post", "cash", 5, "CLIENT", {"http.method": "POST"}),
            span(7, "http post", "accounts", 6, "SERVER", {"http.method": "POST"}),
            span(8, "query", "accounts", 7, tags={"jdbc.query": "select"}),
            span(9, "notifications publish", "accounts", 7, "PRODUCER", {"messaging.system": "kafka"}),
            span(10, "notifications receive", "notifications", 9, "CONSUMER", {"messaging.system": "kafka"})]


class TraceAndLogTest(unittest.TestCase):
    def test_only_actual_kafka_spans_count_as_messaging_not_internal_relay_receivers(self):
        trace = trace_fixture()
        relay = copy.deepcopy(trace[-1])
        relay.update(name="bank.outbox.relay", tags={})
        relay["id"] = "f" * 16
        trace.append(relay)
        facts, problems = observability.analyze_traces([trace], {"cash", "notifications"}, ())
        self.assertEqual([], problems)
        self.assertEqual(1, facts["kinds"]["kafka-consumer"])
        trace[-2]["tags"]["messaging.system"] = "other"
        facts, problems = observability.analyze_traces([trace], {"cash", "notifications"}, ())
        self.assertEqual(0, facts["chains"])
        self.assertTrue(problems)

    def test_requires_connected_trace_and_matching_identifiers(self):
        good = trace_fixture()
        facts, problems = observability.analyze_traces([good], {"cash", "notifications"}, ())
        self.assertEqual([], problems)
        self.assertEqual(1, facts["chains"])
        cycle = copy.deepcopy(good)
        cycle[-2]["parentId"] = cycle[-1]["id"]
        mixed = copy.deepcopy(good)
        mixed[-1]["traceId"] = "2" * 32
        invalid = copy.deepcopy(good)
        invalid[-1]["id"] = "0" * 16
        missing_hop = copy.deepcopy(good)
        missing_hop[2]["localEndpoint"]["serviceName"] = "other"
        bypassed_gateway = copy.deepcopy(good)
        bypassed_gateway[4]["parentId"] = good[0]["id"]
        unrelated_jdbc = copy.deepcopy(good)
        unrelated_jdbc[-3]["parentId"] = good[0]["id"]
        unrelated_kafka = copy.deepcopy(good)
        unrelated_kafka[-2]["parentId"] = good[0]["id"]
        cases = {"cycle": cycle, "mixed trace ids": mixed, "zero span id": invalid, "missing service": missing_hop,
                 "bypassed gateway": bypassed_gateway, "unrelated JDBC": unrelated_jdbc, "unrelated Kafka": unrelated_kafka}
        for name, broken in cases.items():
            with self.subTest(case=name):
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
        cases = ((200, [], 0), (404, {}, 0), (200, trace, 1), (200, [trace[0]], 0),
                 (200, [{**trace[0], "traceId": "2" * 32}], 0))
        for status, returned, matches in cases:
            with self.subTest(status=status, returned=returned), api(lambda path, body: (status, returned)) as port:
                self.assertEqual(matches, observability.correlated_logs(port, [document]))

    def test_log_correlation_samples_recent_pairs_before_retired_traces(self):
        recent = {"traceId": "f" * 32, "spanId": "f" * 16}
        stale = [{"traceId": f"{index:032x}", "spanId": f"{index:016x}"}
                 for index in range(1, observability.LOG_TRACE_SAMPLE + 2)]
        requests = []
        def respond(path, body):
            requests.append(path)
            if path.endswith(recent["traceId"]):
                return 200, [{"traceId": recent["traceId"], "id": recent["spanId"]}]
            return 404, {}
        with api(respond) as port:
            self.assertEqual(1, observability.correlated_logs(port, [recent, recent, *stale]))
        self.assertEqual(observability.LOG_TRACE_SAMPLE, len(requests))


class RuntimeBoundaryTest(unittest.TestCase):
    def test_log_delivery_separates_nonretryable_history_quarantine_and_real_bank_debug(self):
        project = observability.PROJECT
        services = observability.expected_services(observability.load_yaml(project / "deployment/charts/bank/values.yaml"), False)
        documents = [{"service": name, "level": "DEBUG", "event": "Safe diagnostic", "logger_name": "dev.principalwater.bank.runtime.workflow.MoneyWorkflow",
                      "@timestamp": "2026-10-21T10:00:00Z", "traceId": "1" * 32, "spanId": "1" * 16} for name in services]
        wanted = observability.pipeline_ids(project)
        cases = ((0, 0, 99, "DEBUG", "dev.principalwater.bank.runtime.workflow.MoneyWorkflow", "test", True, True),
                 (0, 1, 99, "DEBUG", "dev.principalwater.bank.runtime.workflow.MoneyWorkflow", "test", True, True),
                 (23, 0, 99, "DEBUG", "dev.principalwater.bank.runtime.workflow.MoneyWorkflow", "test", True, True),
                 (0, 256, 0, "DEBUG", "dev.principalwater.bank.runtime.workflow.MoneyWorkflow", "test", False, True),
                 (0, None, 0, "DEBUG", "dev.principalwater.bank.runtime.workflow.MoneyWorkflow", "test", False, True),
                 (0, -1, 0, "DEBUG", "dev.principalwater.bank.runtime.workflow.MoneyWorkflow", "test", False, True),
                 (0, 0, 0, "INFO", "dev.principalwater.bank.runtime.workflow.MoneyWorkflow", "dev", True, False),
                 (0, 0, 0, "DEBUG", "org.springframework.context", "test", True, False),
                 (0, 0, 0, "INFO", "dev.principalwater.bank.runtime.workflow.MoneyWorkflow", "prod", True, True))
        for routed, size, non_retryable, level, logger, environment, accepted, indexed in cases:
            with self.subTest(routed=routed, size=size, environment=environment, level=level, logger=logger):
                current = [{**item, "level": level, "logger_name": logger} for item in documents]
                pipeline = {"events": {"out": 10}, "dead_letter_queue": {"queue_size_in_bytes": size},
                            "plugins": {"inputs": [{"id": name} for name in wanted], "filters": [],
                                        "outputs": [{"id": "bank-logs-elasticsearch", "documents": {"non_retryable_failures": non_retryable, "dlq_routed": routed}}]}}
                def respond(path, body, headers):
                    if path == "/_node/stats/pipelines":
                        return 200, {"pipelines": {"main": pipeline}}
                    if path == "/bank11-logs-*/_search":
                        return (200, {"hits": {"total": {"value": len(documents)}, "hits": [{"_source": item} for item in current]}}) if headers.get("Authorization") else (401, {})
                    return 200, [{"traceId": "1" * 32, "id": "1" * 16}]
                report = observability.Report()
                with api(respond, with_headers=True) as port, redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                    stack = observability.Stack(project, {"namespace": f"bank11-{environment}"}, Path("."), {"namespace": f"bank11-{environment}"},
                                                SimpleNamespace(port=lambda _: port), lambda *_: "fixture-password")
                    stack.deadline = 0
                    observability.check_logs(stack, report)
                checks = [check for check in report.checks if check["name"] == "logstash pipeline"]
                self.assertEqual("PASS" if accepted else "FAIL", checks[0]["status"])
                if accepted:
                    self.assertEqual(routed, report.facts["logstash"]["dlq_routed"])
                    self.assertEqual(non_retryable, report.facts["logstash"]["non_retryable_failures"])
                indexed_checks = [check for check in report.checks if check["name"] == "elasticsearch logs"]
                self.assertEqual("PASS" if indexed else "FAIL", indexed_checks[0]["status"])

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
