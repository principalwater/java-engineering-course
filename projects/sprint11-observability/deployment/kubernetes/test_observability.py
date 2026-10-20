"""Contracts of the observability assets and their rendered chart objects, checked without a daemon.

The static checks read the zipkin/, prometheus/, grafana/ and elk/ directories; the rendered checks run
`helm template` with those directories exposed as the bank11-zipkin, bank11-prometheus, bank11-grafana and
bank11-elk asset ConfigMaps (every regular file directly inside a directory, subdirectories such as
prometheus/tests are not assets). promtool and amtool are the official binaries; their tests are skipped,
with the reason stated, on a machine that does not have them.
"""

import json
import re
import shutil
import subprocess
import tempfile
import unittest
import xml.dom.minidom
from functools import lru_cache
from pathlib import Path

import yaml

from prepare import prepare, project_dir


PROJECT = project_dir()
CHART = PROJECT / "deployment/charts/bank"
ASSET_DIRECTORIES = {"bank11-zipkin": "zipkin", "bank11-prometheus": "prometheus",
                     "bank11-grafana": "grafana", "bank11-elk": "elk"}
PROMTOOL = shutil.which("promtool")
AMTOOL = shutil.which("amtool")

# Secret objects and the files (keys) the observability workloads read from them. kubernetes/secret-bindings.json
# must provide exactly these; the chart creates none of them.
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
# 12 GiB VM minus kernel, kind node daemons and page cache.
VM_MEMORY_MIB = 12 * 1024
RESERVED_MIB = 2 * 1024
STRIMZI_OPERATOR_MIB = 384


def asset_files(directory: str) -> dict[str, Path]:
    return {path.name: path for path in sorted((PROJECT / directory).iterdir()) if path.is_file()}


def asset_text(directory: str, name: str) -> str:
    return (PROJECT / directory / name).read_text(encoding="utf-8")


def mebibytes(quantity: str) -> float:
    match = re.fullmatch(r"(\d+(?:\.\d+)?)(Mi|Gi|M|G)?", str(quantity))
    value, unit = float(match.group(1)), match.group(2)
    return value * {"Mi": 1, "Gi": 1024, "M": 1000 ** 2 / 1024 ** 2, "G": 1000 ** 3 / 1024 ** 2, None: 1 / 1024 ** 2}[unit]


@lru_cache(maxsize=None)
def render(analytics: bool, overrides: tuple = ()) -> tuple:
    with tempfile.TemporaryDirectory(prefix="bank11-observability-") as directory:
        destination = Path(directory) / "values"
        prepare(destination, environment="dev", profile="single", analytics=analytics, superset=False, external_port=8460)
        values = yaml.safe_load((destination / "public-values.yaml").read_text(encoding="utf-8"))
        for config_map, source in ASSET_DIRECTORIES.items():
            values["assets"][config_map] = {name: path.read_text(encoding="utf-8") for name, path in asset_files(source).items()}
        override_file = Path(directory) / "values-with-observability.yaml"
        override_file.write_text(yaml.safe_dump(values), encoding="utf-8")
        arguments = [argument for override in overrides for argument in ("--set", override)]
        rendered = subprocess.run(["helm", "template", "bank", str(CHART), "--namespace", "bank11-dev",
                                   "-f", str(override_file), *arguments], capture_output=True, text=True, check=True)
    return tuple(item for item in yaml.safe_load_all(rendered.stdout) if isinstance(item, dict))


def objects(analytics: bool = False, kind: str = None, overrides: tuple = ()) -> dict:
    return {item["metadata"]["name"]: item for item in render(analytics, overrides) if kind is None or item["kind"] == kind}


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
                        subprocess.run([shell, "-n", str(path)], check=True, capture_output=True, text=True)

    def test_alert_rules_name_a_severity_and_a_first_diagnostic_step(self):
        jobs = {job["job_name"] for job in yaml.safe_load(asset_text("prometheus", "prometheus.yml"))["scrape_configs"]}
        rules = alert_rules()
        self.assertGreaterEqual(len(rules), 30)
        for name, rule in rules:
            with self.subTest(alert=rule["alert"]):
                self.assertIn(rule["labels"]["severity"], {"critical", "warning"})
                for annotation in ("summary", "description", "action"):
                    self.assertTrue(rule["annotations"].get(annotation), annotation)
                # A typo in a job selector would silently disable the alert.
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
                        # A missing series must show as no data; a vector(0) fallback would read as "all fine".
                        self.assertNotIn("vector(", target.get("expr", ""))

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

    def test_logstash_allowlist_equals_the_strict_index_mapping(self):
        # dynamic: strict makes Elasticsearch reject any document with an unmapped field, so a field allowed
        # through the pipeline but absent from the template (or the reverse) floods the dead letter queue.
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
            # kibana-bootstrap.sh strips the leading type/id pair with sed, so the key order is part of the contract.
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
        result = subprocess.run([PROMTOOL, *arguments], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_rules_and_their_unit_tests_pass(self):
        self.promtool("check", "rules", *[str(path) for name, path in asset_files("prometheus").items() if name.endswith(".rules.yml")])
        tests = sorted((PROJECT / "prometheus/tests").glob("*.test.yml"))
        self.assertTrue(tests)
        self.promtool("test", "rules", *map(str, tests))

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
        text = objects(kind="ConfigMap", overrides=("observability.alerting.telegram.enabled=true",))["alertmanager-config"]["data"]["alertmanager.yml"]
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
            result = subprocess.run([AMTOOL, "check-config", str(path)], capture_output=True, text=True)
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
        override = ("observability.alerting.telegram.enabled=true",
                    "observability.alerting.telegram.apiUrl=http://telegram-fake:8081")
        text = objects(kind="ConfigMap", overrides=override)["alertmanager-config"]["data"]["alertmanager.yml"]
        self.assertEqual(yaml.safe_load(text)["global"]["telegram_api_url"], "http://telegram-fake:8081")
        self.assertNotIn("@TELEGRAM_API_URL@", objects(kind="ConfigMap")["alertmanager-config"]["data"]["alertmanager.yml"])

    def test_monitoring_update_preserves_application_pods_but_changed_client_configuration_rolls_them(self):
        current = objects()
        monitoring = objects(overrides=("global.chartRevision=" + "a" * 64,
                                        "observability.retention.metricsTime=6d"))
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

    def test_logging_can_be_switched_off_without_touching_metrics_and_traces(self):
        workloads = self.workloads(overrides=("observability.logging.enabled=false",))
        self.assertEqual(set(workloads), LONG_RUNNING - {"elasticsearch", "kibana", "logstash"})
        names = set(objects(overrides=("observability.logging.enabled=false",)))
        self.assertFalse({"elasticsearch-data", "logstash-data"} & names)

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
