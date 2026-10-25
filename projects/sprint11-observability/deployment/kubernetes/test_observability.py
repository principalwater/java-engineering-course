"""Проверка ресурсов наблюдаемости и объектов из шаблонов чарта без работающего стенда.

Статические проверки читают каталоги observability/{zipkin,prometheus,grafana,elk}/; проверки шаблонов запускают
`helm template` с файлами из этих каталогов в ConfigMap bank11-zipkin, bank11-prometheus, bank11-grafana и
bank11-elk. Включаются только обычные файлы верхнего уровня; подкаталоги вроде observability/prometheus/tests исключены.
Используются официальные promtool и amtool; при их отсутствии тесты пропускаются с указанием причины.
"""

import base64
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import re
import shutil
import subprocess
import tempfile
import threading
import unittest
import xml.dom.minidom
from functools import lru_cache
from pathlib import Path

import yaml

from prepare import prepare, project_dir


PROJECT = project_dir()
CHART = PROJECT / "deployment/charts/bank"
OBSERVABILITY = PROJECT / "observability"
ASSET_DIRECTORIES = {"bank11-zipkin": "zipkin", "bank11-prometheus": "prometheus",
                     "bank11-grafana": "grafana", "bank11-elk": "elk"}
PROMTOOL = shutil.which("promtool")
AMTOOL = shutil.which("amtool")

# Объекты Secret и их ключи, читаемые компонентами наблюдаемости. kubernetes/secret-bindings.json
# должен предоставлять именно их; чарт не создаёт эти секреты.
SECRET_CONTRACT = {
    "bank11-prometheus": {"grafana_metrics_password"},
    "bank11-alertmanager": {"telegram_bot_token", "telegram_chat_id"},
    "bank11-grafana": {"grafana_admin_password", "grafana_operator_password", "grafana_secret_key",
                       "grafana_metrics_password", "grafana_es_reader_password"},
    "bank11-elasticsearch": {"elastic_password", "kibana_system_password", "logstash_writer_password",
                             "grafana_es_reader_password", "elk_operator_password", "kibana_provisioner_password"},
    "bank11-logstash": {"logstash_writer_password", "tls.crt", "tls.key"},
    "bank11-kibana": {"kibana_system_password", "kibana_encryption_key", "kibana_provisioner_password"},
}
LONG_RUNNING = {"zipkin", "prometheus", "alertmanager", "grafana", "elasticsearch", "kibana", "logstash"}
DATASOURCES = {"bank-prometheus": "prometheus", "bank-alertmanager": "alertmanager",
               "bank-zipkin": "zipkin", "bank-logs": "elasticsearch"}
# Из 12 ГиБ VM вычтены ядро, службы узла kind и файловый кеш.
VM_MEMORY_MIB = 12 * 1024
RESERVED_MIB = 2 * 1024
STRIMZI_OPERATOR_MIB = 384


def asset_files(directory: str) -> dict[str, Path]:
    return {path.name: path for path in sorted((OBSERVABILITY / directory).iterdir()) if path.is_file()}


def asset_text(directory: str, name: str) -> str:
    return (OBSERVABILITY / directory / name).read_text(encoding="utf-8")


def mebibytes(quantity: str) -> float:
    match = re.fullmatch(r"(\d+(?:\.\d+)?)(Mi|Gi|M|G)?", str(quantity))
    value, unit = float(match.group(1)), match.group(2)
    return value * {"Mi": 1, "Gi": 1024, "M": 1000 ** 2 / 1024 ** 2, "G": 1000 ** 3 / 1024 ** 2, None: 1 / 1024 ** 2}[unit]


@lru_cache(maxsize=None)
def render(analytics: bool, overrides: tuple = (), environment: str = "dev") -> tuple:
    with tempfile.TemporaryDirectory(prefix="bank11-observability-") as directory:
        destination = Path(directory) / "values"
        prepare(destination, environment=environment, profile="single", analytics=analytics, superset=False, external_port=8460)
        values = yaml.safe_load((destination / "public-values.yaml").read_text(encoding="utf-8"))
        override_file = Path(directory) / "values-with-observability.yaml"
        override_file.write_text(yaml.safe_dump(values), encoding="utf-8")
        arguments = [argument for override in overrides for argument in ("--set", override)]
        rendered = subprocess.run(["helm", "template", "bank", str(CHART), "--namespace", f"bank11-{environment}",
                                   "-f", str(override_file), *arguments], capture_output=True, text=True, encoding="utf-8", check=True)
    return tuple(item for item in yaml.safe_load_all(rendered.stdout) if isinstance(item, dict))


def objects(analytics: bool = False, kind: str = None, overrides: tuple = (), environment: str = "dev") -> dict:
    return {item["metadata"]["name"]: item for item in render(analytics, overrides, environment) if kind is None or item["kind"] == kind}


def containers(workload: dict) -> list:
    spec = workload["spec"]["template"]["spec"]
    return spec.get("initContainers", []) + spec["containers"]


def dashboards() -> dict:
    return {name: json.loads(path.read_text(encoding="utf-8"))
            for name, path in asset_files("grafana").items() if name.endswith(".json")}


def panels(dashboard: dict):
    for panel in dashboard["panels"]:
        yield panel
        yield from panel.get("panels", [])


def alert_rules() -> list:
    rules = []
    for name, path in asset_files("prometheus").items():
        if name.endswith(".rules.yml"):
            for group in yaml.safe_load(path.read_text(encoding="utf-8"))["groups"]:
                rules.extend((name, rule) for rule in group["rules"] if "alert" in rule)
    return rules


class AssetContractTest(unittest.TestCase):
    def test_every_asset_parses_and_scripts_have_valid_syntax(self):
        for directory in ASSET_DIRECTORIES.values():
            for name, path in asset_files(directory).items():
                with self.subTest(asset=f"{directory}/{name}"):
                    text = path.read_text(encoding="utf-8")
                    if name.endswith((".yml", ".yaml")):
                        yaml.safe_load(text)
                    elif name.endswith(".json"):
                        json.loads(text)
                    elif name.endswith(".xml"):
                        xml.dom.minidom.parseString(text)
                    elif name.endswith(".ndjson"):
                        for line in text.splitlines():
                            json.loads(line)
                    elif name.endswith(".sh"):
                        shell = "bash" if text.startswith("#!/bin/bash") else "sh"
                        subprocess.run([shell, "-n", str(path)], check=True, capture_output=True, text=True, encoding="utf-8")

    def test_alert_rules_name_a_severity_and_a_first_diagnostic_step(self):
        jobs = {job["job_name"] for job in yaml.safe_load(asset_text("prometheus", "prometheus.yml"))["scrape_configs"]}
        rules = alert_rules()
        self.assertGreaterEqual(len(rules), 30)
        for name, rule in rules:
            with self.subTest(alert=rule["alert"]):
                self.assertIn(rule["labels"]["severity"], {"critical", "warning"})
                for annotation in ("summary", "description", "action"):
                    self.assertTrue(rule["annotations"].get(annotation), annotation)
                # Опечатка в селекторе job незаметно отключила бы оповещение.
                for operator, selector in re.findall(r'\bjob(=~|=)"([^"]+)"', rule["expr"]):
                    matching = [job for job in jobs if (re.fullmatch(selector, job) if operator == "=~" else selector == job)]
                    self.assertTrue(matching, f"job{operator}{selector} matches no scrape job of prometheus.yml")

    def test_dashboards_query_provisioned_datasources_with_real_expressions(self):
        provisioned = {item["uid"]: item["type"] for item in yaml.safe_load(asset_text("grafana", "datasources.yaml"))["datasources"]}
        self.assertEqual(provisioned, DATASOURCES)
        seen_uids = set()
        for name, dashboard in dashboards().items():
            with self.subTest(dashboard=name):
                self.assertTrue(dashboard["uid"].startswith("bank-") and dashboard["uid"] not in seen_uids)
                seen_uids.add(dashboard["uid"])
                self.assertFalse(dashboard["editable"])
                self.assertIn("bank11", dashboard["tags"])
                for panel in panels(dashboard):
                    if panel["type"] == "row":
                        continue
                    self.assertIn(panel["datasource"]["uid"], provisioned)
                    for target in panel["targets"]:
                        self.assertIn(target["datasource"]["uid"], provisioned)
                        # Отсутствие ряда должно давать «нет данных»; vector(0) создал бы ложное благополучие.
                        self.assertNotIn("vector(", target.get("expr", ""))
                        # Grafana объединяет table-ряды в колонку Value; lastNotNull оставляет лишь последнюю метку.
                        if panel["type"] == "bargauge" and "{{" in target.get("legendFormat", ""):
                            with self.subTest(panel=panel["title"]):
                                self.assertTrue(
                                    target.get("format", "time_series") != "table"
                                    or panel.get("options", {}).get("reduceOptions", {}).get("values"),
                                    "Labelled bar gauge must preserve each series or display every table row")

    def test_http_dashboard_charts_error_rates_and_error_latency_quantiles(self):
        expressions = [target["expr"] for panel in panels(dashboards()["bank-http.json"]) if panel["type"] != "row"
                       for target in panel["targets"]]
        for status_class in ("4..", "5.."):
            with self.subTest(status_class=status_class):
                self.assertTrue(any(f'status=~"{status_class}"' in expression and "histogram_quantile(0.99" in expression
                                    for expression in expressions), "p99 of failed requests")
                self.assertTrue(any(f'status=~"{status_class}"' in expression and "rate(" in expression
                                    and "histogram_quantile" not in expression for expression in expressions), "error rate")

    def test_grafana_datasources_keep_credentials_in_files(self):
        for datasource in yaml.safe_load(asset_text("grafana", "datasources.yaml"))["datasources"]:
            with self.subTest(datasource=datasource["uid"]):
                self.assertFalse(datasource["editable"])
                for value in datasource.get("secureJsonData", {}).values():
                    self.assertTrue(str(value).startswith("$__file{/run/secrets/"))

    def test_grafana_bootstrap_grants_folder_view_without_promoting_the_operator(self):
        """21.10.2026: Grafana 13.2.4 создаёт Bank11 без ACL, поэтому Viewer не видит панели."""
        folder = yaml.safe_load(asset_text("grafana", "dashboards.yaml"))["providers"][0]["folderUid"]
        state = {"role": "Editor", "permissions": [], "deny": False, "folder_reads": 0, "folder_error": None}
        password = "fixture-admin-password"
        reflected = "private-reflected-password"
        authorization = "Basic " + base64.b64encode(f"bank11-admin:{password}".encode()).decode()

        class Grafana(BaseHTTPRequestHandler):
            def respond(self, status, body):
                encoded = json.dumps(body).encode()
                self.send_response(status)
                self.send_header("Content-Length", str(len(encoded)))
                self.end_headers()
                self.wfile.write(encoded)

            def dispatch(self):
                if self.path == "/api/health":
                    return self.respond(200, {"database": "ok"})
                if self.headers.get("Authorization") != authorization:
                    return self.respond(401, {})
                size = int(self.headers.get("Content-Length", 0))
                body = json.loads(self.rfile.read(size)) if size else {}
                if self.command == "GET" and self.path.startswith("/api/users/lookup?"):
                    return self.respond(200, {"id": 7})
                if self.command == "GET" and self.path == f"/api/folders/{folder}":
                    if state["folder_error"]:
                        return self.respond(state["folder_error"], {"message": reflected})
                    state["folder_reads"] += 1
                    return self.respond(404 if state["folder_reads"] == 1 else 200, {"uid": folder})
                if self.command == "PUT" and self.path == "/api/admin/users/7/password":
                    return self.respond(200, {})
                if self.command == "PATCH" and self.path == "/api/org/users/7":
                    state["role"] = body.get("role")
                    return self.respond(200, {})
                if self.command == "POST" and self.path == f"/api/folders/{folder}/permissions":
                    if state["folder_reads"] < 2:
                        return self.respond(404, {"message": reflected})
                    if state["deny"]:
                        return self.respond(403, {"message": reflected})
                    state["permissions"] = body.get("items")
                    return self.respond(200, {})
                self.respond(404, {})

            do_GET = do_PUT = do_PATCH = do_POST = dispatch

            def log_message(self, *_):
                pass

        with tempfile.TemporaryDirectory() as directory, HTTPServer(("127.0.0.1", 0), Grafana) as server:
            root = Path(directory)
            (root / "grafana_admin_password").write_text(password, encoding="utf-8")
            (root / "grafana_operator_password").write_text("fixture-operator-password", encoding="utf-8")
            # Выполняется сам asset; адрес сервиса и точка монтирования секретов заменяются на fixture.
            script = asset_text("grafana", "grafana-bootstrap.sh").replace(
                "http://grafana:3000", f"http://127.0.0.1:{server.server_port}").replace("/run/secrets", str(root))
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            try:
                for denied, folder_error in ((False, None), (False, None), (True, None), (False, 503)):
                    with self.subTest(permission_denied=denied, folder_error=folder_error):
                        state["deny"] = denied
                        state["folder_error"] = folder_error
                        result = subprocess.run(["bash"], input=script, text=True, encoding="utf-8", capture_output=True, timeout=20)
                        output = result.stdout + result.stderr
                        self.assertNotIn(reflected, output)
                        if denied or folder_error:
                            self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                            self.assertIn(f"HTTP {folder_error or 403}", output)
                        else:
                            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                            self.assertEqual(state["role"], "Viewer")
                            self.assertEqual(state["permissions"], [{"role": "Viewer", "permission": 1}])
            finally:
                server.shutdown()
                worker.join()

    def test_logstash_allowlist_equals_the_strict_index_mapping(self):
        # dynamic: strict отклоняет документы с полями вне mapping; расхождение списка полей pipeline
        # и шаблона заполнило бы очередь недоставленных документов.
        pipeline = asset_text("elk", "bank-logs.conf")
        allowed = set(re.findall(r'"\^([A-Za-z@_]+)\$"', pipeline[pipeline.index("prune {"):]))
        template = json.loads(asset_text("elk", "index-template.json"))["template"]["mappings"]
        self.assertEqual(template["dynamic"], "strict")
        self.assertEqual(allowed, set(template["properties"]))
        self.assertLess(pipeline.index("prune {"), pipeline.index("output {"))
        self.assertLess(pipeline.index("gsub"), pipeline.index("output {"))
        for keyword in ("traceId", "spanId", "service"):
            self.assertEqual(template["properties"][keyword]["type"], "keyword")

    def test_elasticsearch_requires_authentication_and_kibana_uses_its_service_identity(self):
        settings = yaml.safe_load(asset_text("elk", "elasticsearch.yml"))
        self.assertIs(settings["xpack.security.enabled"], True)
        self.assertFalse([key for key in settings if "anonymous" in key])
        self.assertEqual(yaml.safe_load(asset_text("elk", "kibana.yml"))["elasticsearch.username"], "kibana_system")

    def test_kibana_saved_objects_reference_each_other_and_the_data_view(self):
        data_view = json.loads(asset_text("elk", "kibana-data-view.json"))["data_view"]
        objects_by_id = {}
        for line in asset_text("elk", "kibana-objects.ndjson").splitlines():
            # kibana-bootstrap.sh удаляет начальные type/id через sed, поэтому порядок ключей значим.
            self.assertRegex(line, r'^\{"type":"[a-z-]+","id":"[A-Za-z0-9_-]+","attributes":')
            item = json.loads(line)
            objects_by_id[item["id"]] = item
        self.assertEqual(data_view["title"], "bank11-logs-*")
        for item in objects_by_id.values():
            for reference in item["references"]:
                if reference["type"] == "index-pattern":
                    self.assertEqual(reference["id"], data_view["id"])
                else:
                    self.assertIn(reference["id"], objects_by_id)
        dashboard = objects_by_id["bank11-logs-overview"]
        self.assertEqual(len(json.loads(dashboard["attributes"]["panelsJSON"])), len(dashboard["references"]))


@unittest.skipUnless(PROMTOOL, "promtool is not installed; install the official Prometheus release to run this check")
class PromtoolTest(unittest.TestCase):
    def promtool(self, *arguments):
        result = subprocess.run([PROMTOOL, *arguments], capture_output=True, text=True, encoding="utf-8")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_rules_and_their_unit_tests_pass(self):
        self.promtool("check", "rules", *[str(path) for name, path in asset_files("prometheus").items() if name.endswith(".rules.yml")])
        tests = sorted((OBSERVABILITY / "prometheus/tests").glob("*.test.yml"))
        self.assertTrue(tests)
        self.promtool("test", "rules", *map(str, tests))

    def test_topic_volume_all_excludes_unlabeled_broker_totals(self):
        """Общий счётчик брокера не является топиком и иначе удваивает отображаемый трафик."""
        dashboard = dashboards()["bank-kafka-topics.json"]
        topic = next(item for item in dashboard["templating"]["list"] if item["name"] == "topic")
        panel = next(item for item in panels(dashboard) if item["title"] == "Messages in per second by topic")
        expression = panel["targets"][0]["expr"].replace("$topic", topic["allValue"]).replace("$__rate_interval", "1m")
        metric = "kafka_server_brokertopicmetrics_messagesin_total"
        fixture = {"evaluation_interval": "15s", "fuzzy_compare": True, "tests": [{"interval": "15s", "input_series": [
            {"series": metric + '{instance="broker-0"}', "values": "0+300x4"},
            {"series": metric + '{instance="broker-0",topic="cash.v1"}', "values": "0+30x4"},
            {"series": metric + '{instance="broker-0",topic="transfer.v1"}', "values": "0+60x4"},
        ], "promql_expr_test": [{"expr": expression, "eval_time": "1m", "exp_samples": [
            {"labels": '{topic="cash.v1"}', "value": 2},
            {"labels": '{topic="transfer.v1"}', "value": 4},
        ]}]}]}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "topic-volume.test.yml"
            path.write_text(yaml.safe_dump(fixture), encoding="utf-8")
            self.promtool("test", "rules", str(path))

    def test_business_totals_and_top_lists_keep_retired_series_and_agree_at_the_same_time(self):
        # Перезапуск Pod не стирает события внутри окна; stat и top должны запрашивать один instant.
        dashboard = dashboards()["bank-business.json"]
        scenarios = (
            ("new growing", ("_x5 1 2 3 4",), "9m", 4),
            ("new constant", ("_x5 1x3",), "9m", 1),
            ("retired growing", ("_x5 1 2 3 stale _x2",), "10m", 3),
            ("retired single", ("_x5 1 stale _x3",), "10m", 1),
            ("known single", ("100 _x4 103 stale _x3",), "10m", 3),
            ("known unchanged single", ("100 _x4 100 stale _x3",), "10m", 0),
            ("known single after reset", ("100 _x4 2 stale _x3",), "10m", 2),
            ("reset with baseline", ("0 2 4 0 1 2 3 4 5 6 7",), "10m", 10),
            ("new reset estimate", ("_x5 1 2 0 1",), "9m", 3),
            ("retired and current instances", ("_x5 1 2 3 stale _x2", "_x7 1 2"), "10m", 5),
            ("expired", ("_x5 1 stale _x20",), "20m", None),
        )
        tests = []
        for metric, label, total_id, top_id in (
                ("bank_cash_withdrawal_failures_total", "login", 2, 3),
                ("bank_transfer_failures_total", "sender", 7, 8),
                ("bank_notification_failures_total", "login", 14, 15)):
            total = next(panel for panel in panels(dashboard) if panel["id"] == total_id)["targets"][0]
            top = next(panel for panel in panels(dashboard) if panel["id"] == top_id)["targets"][0]
            for name, values, at, expected in scenarios:
                tests.append({"name": f"{metric}: {name}", "interval": "1m", "input_series": [
                    {"series": metric + '{' + f'{label}="subject",instance="pod-{index}"' + '}', "values": value}
                    for index, value in enumerate(values)], "promql_expr_test": [
                        {"expr": target["expr"].replace("$__range", "10m"), "eval_time": at,
                         "exp_samples": [] if expected is None else [{"labels": labels, "value": expected}]}
                        for target, labels in ((total, '{}'), (top, '{' + f'{label}="subject"' + '}'))]})
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "business-window.test.yml"
            path.write_text(yaml.safe_dump({"evaluation_interval": "30s", "tests": tests}), encoding="utf-8")
            self.promtool("test", "rules", str(path))
        for identity in (2, 7, 14):
            target = next(panel for panel in panels(dashboard) if panel["id"] == identity)["targets"][0]
            self.assertTrue(target["instant"])
            self.assertFalse(target["range"])

    def test_rendered_scrape_configuration_is_valid(self):
        config_maps = objects(analytics=True, kind="ConfigMap")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "rules").mkdir()
            (root / "targets").mkdir()
            for name, text in config_maps["bank11-prometheus"]["data"].items():
                if name.endswith(".rules.yml"):
                    (root / "rules" / name).write_text(text, encoding="utf-8")
            for name, text in config_maps["prometheus-targets"]["data"].items():
                (root / "targets" / name).write_text(text, encoding="utf-8")
            secret = root / "grafana_metrics_password"
            secret.write_text("x" * 32, encoding="utf-8")
            text = config_maps["bank11-prometheus"]["data"]["prometheus.yml"]
            text = text.replace("/etc/prometheus/", f"{root}/").replace("/run/secrets/grafana_metrics_password", str(secret))
            (root / "prometheus.yml").write_text(text, encoding="utf-8")
            self.promtool("check", "config", str(root / "prometheus.yml"))

    def test_every_dashboard_query_is_valid_promql(self):
        substitutions = (("$__rate_interval", "5m"), ("$__range", "1h"), ("$__interval", "1m"),
                         ("$service", ".*"), ("$topic", ".*"), ("$group", ".*"))
        rules = []
        for name, dashboard in dashboards().items():
            for panel in panels(dashboard):
                for target in panel.get("targets", []):
                    expression = target.get("expr")
                    if expression:
                        for variable, value in substitutions:
                            expression = expression.replace(variable, value)
                        self.assertNotIn("$", expression, f"{name}: {panel['title']}")
                        rules.append({"record": f"check:{len(rules)}", "expr": expression})
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "dashboards.rules.yml"
            path.write_text(yaml.safe_dump({"groups": [{"name": "dashboards", "rules": rules}]}), encoding="utf-8")
            self.promtool("check", "rules", str(path))


@unittest.skipUnless(AMTOOL, "amtool is not installed; install the official Alertmanager release to run this check")
class AlertmanagerTest(unittest.TestCase):
    def test_rendered_configuration_is_valid_and_reads_credentials_from_files(self):
        text = objects(kind="ConfigMap", overrides=("global.observability.alerting.telegram.enabled=true",))["alertmanager-config"]["data"]["alertmanager.yml"]
        config = yaml.safe_load(text)
        for receiver in config["receivers"]:
            for telegram in receiver["telegram_configs"]:
                self.assertEqual(set(telegram) & {"bot_token", "chat_id"}, set())
                self.assertTrue(telegram["bot_token_file"].startswith("/run/secrets/"))
                self.assertTrue(telegram["chat_id_file"].startswith("/run/secrets/"))
        with tempfile.TemporaryDirectory() as directory:
            for name in ("telegram_bot_token", "telegram_chat_id"):
                (Path(directory) / name).write_text("1", encoding="utf-8")
            path = Path(directory) / "alertmanager.yml"
            path.write_text(text.replace("/run/secrets/", f"{directory}/"), encoding="utf-8")
            result = subprocess.run([AMTOOL, "check-config", str(path)], capture_output=True, text=True, encoding="utf-8")
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


class RenderedChartTest(unittest.TestCase):
    def workloads(self, analytics=False, overrides=()):
        return {name: item for name, item in objects(analytics, overrides=overrides).items()
                if name in LONG_RUNNING and item["kind"] in {"Deployment", "StatefulSet"}}

    def test_long_running_containers_are_digest_pinned_bounded_and_probed(self):
        workloads = self.workloads()
        self.assertEqual(set(workloads), LONG_RUNNING)
        for name, workload in workloads.items():
            self.assertEqual(workload["spec"]["replicas"], 1)
            spec = workload["spec"]["template"]["spec"]
            self.assertFalse(spec["automountServiceAccountToken"])
            for container in containers(workload):
                with self.subTest(workload=name, container=container["name"]):
                    self.assertRegex(container["image"], r"@sha256:[a-f0-9]{64}$")
                    resources = container["resources"]
                    for field in ("cpu", "memory"):
                        self.assertIn(field, resources["requests"])
                        self.assertIn(field, resources["limits"])
                    self.assertGreaterEqual(mebibytes(resources["limits"]["memory"]), mebibytes(resources["requests"]["memory"]))
                    self.assertTrue(container["securityContext"]["readOnlyRootFilesystem"])
                    if container in spec["containers"]:
                        for probe in ("startupProbe", "readinessProbe", "livenessProbe"):
                            self.assertIn(probe, container)
                        self.assertNotIn("tcpSocket", container["readinessProbe"])

    def test_deployments_recreate_and_state_lives_in_retained_claims(self):
        for name, workload in self.workloads().items():
            if workload["kind"] == "Deployment":
                self.assertEqual(workload["spec"]["strategy"]["type"], "Recreate", name)
        claims = objects(kind="PersistentVolumeClaim")
        self.assertTrue({"prometheus-data", "alertmanager-data", "grafana-data", "elasticsearch-data", "logstash-data"} <= set(claims))
        for name in ("prometheus-data", "alertmanager-data", "grafana-data", "elasticsearch-data", "logstash-data"):
            self.assertEqual(claims[name]["metadata"]["annotations"]["helm.sh/resource-policy"], "keep")
        mounted = {volume["persistentVolumeClaim"]["claimName"] for workload in self.workloads().values()
                   for volume in workload["spec"]["template"]["spec"]["volumes"] if "persistentVolumeClaim" in volume}
        self.assertEqual(mounted, {"prometheus-data", "alertmanager-data", "grafana-data", "elasticsearch-data", "logstash-data"})
        args = " ".join(containers(self.workloads()["prometheus"])[0]["args"])
        self.assertRegex(args, r"--storage\.tsdb\.retention\.time=\d+d")
        self.assertRegex(args, r"--storage\.tsdb\.retention\.size=\d+")

    def test_secrets_are_referenced_by_name_and_never_created(self):
        self.assertFalse(objects(kind="Secret"))
        referenced = {}
        every_key = object()
        for workload in [*self.workloads().values(), *[job for name, job in objects(kind="Job").items() if "bootstrap" in name]]:
            spec = workload["spec"]["template"]["spec"]
            for volume in spec["volumes"]:
                if "secret" in volume:
                    keys = referenced.setdefault(volume["secret"]["secretName"], set())
                    items = volume["secret"].get("items")
                    keys.update(item["key"] for item in items) if items else keys.add(every_key)
        for secret, keys in referenced.items():
            self.assertIn(secret, SECRET_CONTRACT)
            if every_key in keys:
                continue
            self.assertLessEqual(keys, SECRET_CONTRACT[secret])
        self.assertEqual(set(referenced), set(SECRET_CONTRACT))

    def test_scrape_targets_follow_the_deployed_services(self):
        core = objects(kind="ConfigMap")["prometheus-targets"]["data"]
        web = {group["labels"]["service"] for group in json.loads(core["bank-web.json"])}
        self.assertEqual(web, {"accounts", "cash", "transfer", "gateway", "front"})
        worker = json.loads(core["bank-worker.json"])
        self.assertEqual([group["targets"] for group in worker], [["notifications-metrics:9400"]])
        self.assertEqual(json.loads(core["clickhouse.json"]), [])
        analytics = objects(analytics=True, kind="ConfigMap")["prometheus-targets"]["data"]
        self.assertIn("analytics", {group["labels"]["service"] for group in json.loads(analytics["bank-web.json"])})
        self.assertEqual(json.loads(analytics["clickhouse.json"])[0]["targets"], ["clickhouse-metrics:9363"])
        services = objects(analytics=True, kind="Service")
        self.assertIn("clickhouse-metrics", services)
        self.assertNotIn("clickhouse-metrics", objects(kind="Service"))
        self.assertNotIn("notifications", services, "the worker keeps no application Service; only the metrics Service exists")

    def test_mounts_and_configmap_keys_resolve_and_payloads_fit_the_api_limit(self):
        config_maps = objects(analytics=True, kind="ConfigMap")
        for name, config_map in config_maps.items():
            self.assertLess(sum(len(value.encode("utf-8")) for value in config_map.get("data", {}).values()), 900 * 1024, name)
        bootstrap_jobs = [job for name, job in objects(analytics=True, kind="Job").items() if "bootstrap" in name]
        for workload in [*self.workloads(analytics=True).values(), *bootstrap_jobs]:
            spec = workload["spec"]["template"]["spec"]
            volumes = {volume["name"]: volume for volume in spec["volumes"]}
            for container in containers(workload):
                for mount in container.get("volumeMounts", []):
                    self.assertIn(mount["name"], volumes, f"{workload['metadata']['name']}: {mount['name']}")
            for volume in volumes.values():
                sources = [source["configMap"] for source in volume["projected"]["sources"]] if "projected" in volume else \
                    [volume["configMap"]] if "configMap" in volume else []
                for source in sources:
                    for item in source.get("items", []):
                        self.assertIn(item["key"], config_maps[source["name"]]["data"],
                                      f"{workload['metadata']['name']}: {source['name']}/{item['key']}")

    def test_telegram_receiver_can_point_at_a_local_fake(self):
        override = ("global.observability.alerting.telegram.enabled=true",
                    "global.observability.alerting.telegram.apiUrl=http://telegram-fake:8081")
        text = objects(kind="ConfigMap", overrides=override)["alertmanager-config"]["data"]["alertmanager.yml"]
        self.assertEqual(yaml.safe_load(text)["global"]["telegram_api_url"], "http://telegram-fake:8081")
        self.assertNotIn("@TELEGRAM_API_URL@", objects(kind="ConfigMap")["alertmanager-config"]["data"]["alertmanager.yml"])

    def test_monitoring_update_preserves_application_pods_but_changed_client_configuration_rolls_them(self):
        current = objects()
        monitoring = objects(overrides=("global.chartRevision=" + "a" * 64,
                                        "global.observability.retention.metricsTime=6d"))
        clients = objects(overrides=("global.externalPort=8461",))
        for name, workload in current.items():
            if workload["kind"] != "Deployment":
                continue
            template = workload["spec"]["template"]
            if template["metadata"]["labels"].get("bank11.io/role") == "application":
                self.assertEqual(template, monitoring[name]["spec"]["template"], name)
                self.assertNotEqual(template, clients[name]["spec"]["template"], name)
        self.assertNotEqual(current["prometheus"]["spec"]["template"],
                            monitoring["prometheus"]["spec"]["template"])

    def test_environment_selects_safe_debug_diagnostics_and_sampling_for_every_bank_service(self):
        # Раньше DEBUG существовал только в неактивном профиле local и не доходил до ELK стенда.
        for environment, level, sampling in (("dev", "DEBUG", "1.0"), ("test", "DEBUG", "1.0"), ("prod", "INFO", "0.1")):
            clients = objects(analytics=True, kind="ConfigMap", environment=environment)
            for service in ("accounts", "cash", "transfer", "notifications", "front", "gateway", "analytics"):
                with self.subTest(environment=environment, service=service):
                    self.assertEqual(level, clients[f"{service}-client"]["data"]["LOGGING_LEVEL_DEV_PRINCIPALWATER_BANK"])
                    self.assertEqual(sampling, clients[f"{service}-client"]["data"]["MANAGEMENT_TRACING_SAMPLING_PROBABILITY"])

    def test_observability_switches_disable_exporters_with_their_destination_workloads(self):
        for enabled, logging in ((True, True), (True, False), (False, True), (False, False)):
            with self.subTest(observability=enabled, logging=logging):
                overrides = (f"global.observability.enabled={str(enabled).lower()}",
                             f"global.observability.logging.enabled={str(logging).lower()}")
                workloads = self.workloads(analytics=True, overrides=overrides)
                expected = LONG_RUNNING if logging else LONG_RUNNING - {"elasticsearch", "kibana", "logstash"}
                self.assertEqual(set(workloads), expected if enabled else set())
                resources = objects(analytics=True, overrides=overrides)
                if not enabled or not logging:
                    self.assertFalse({"elasticsearch-data", "logstash-data"} & set(resources))
                for service in ("accounts", "cash", "transfer", "notifications", "front", "gateway", "analytics"):
                    environment = resources[f"{service}-client"]["data"]
                    self.assertEqual(str(enabled).lower(), environment["MANAGEMENT_ZIPKIN_TRACING_EXPORT_ENABLED"])
                    self.assertEqual("tls" if enabled and logging else "none", environment["BANK_LOGGING_LOGSTASH_TRANSPORT"])

    def test_memory_requests_fit_the_vm_after_the_rest_of_the_release(self):
        for analytics in (False, True):
            requested = STRIMZI_OPERATOR_MIB
            for item in render(analytics):
                if item["kind"] in {"Deployment", "StatefulSet"}:
                    requested += sum(mebibytes(c["resources"]["requests"]["memory"]) for c in containers(item))
                elif item["kind"] == "KafkaNodePool":
                    requested += item["spec"]["replicas"] * mebibytes(item["spec"]["resources"]["requests"]["memory"])
                elif item["kind"] == "Kafka":
                    spec = item["spec"]["entityOperator"]
                    requested += sum(mebibytes(spec[operator]["resources"]["requests"]["memory"]) for operator in ("topicOperator", "userOperator"))
            with self.subTest(analytics=analytics):
                self.assertLessEqual(requested, VM_MEMORY_MIB - RESERVED_MIB, f"{requested:.0f} MiB requested")


if __name__ == "__main__":
    unittest.main()
