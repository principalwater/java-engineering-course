"""Test rendered deployment contracts before any Kubernetes lifecycle operation."""

import copy
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

import yaml

from prepare import prepare, project_dir


class BankChartTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory(prefix="bank11-chart-")
        cls.project = project_dir()
        cls.models = {}
        cls.sources = {}
        for profile, analytics, superset in (("single", False, False), ("single", True, True), ("ha", False, False)):
            destination = Path(cls.directory.name) / f"{profile}-{analytics}"
            prepare(destination, environment="dev", profile=profile, analytics=analytics,
                    superset=superset, external_port=8460)
            rendered = subprocess.run(["helm", "template", "bank", str(cls.project / "deployment/charts/bank"),
                                       "--namespace", "bank11-dev", "-f", str(destination / "public-values.yaml")],
                                      capture_output=True, text=True, check=True)
            cls.models[(profile, analytics)] = list(yaml.safe_load_all(rendered.stdout))
            cls.sources[(profile, analytics)] = {}
            for document in re.split(r"(?m)^---\s*$", rendered.stdout):
                resource = yaml.safe_load(document)
                if isinstance(resource, dict):
                    source = re.search(r"(?m)^# Source: (.+)$", document).group(1)
                    cls.sources[(profile, analytics)][(resource["kind"], resource["metadata"]["name"])] = source

    @classmethod
    def tearDownClass(cls):
        cls.directory.cleanup()

    def resources(self, profile="single", analytics=False, kind=None):
        objects = self.models[(profile, analytics)]
        return {item["metadata"]["name"]: item for item in objects if item and item["kind"] == kind}

    def test_observability_resources_are_owned_by_real_application_subcharts(self):
        """Ресурсы принадлежат дочерним чартам; одних записей в dependencies для этого недостаточно."""
        owners = {("Deployment", "zipkin"): "zipkin", ("StatefulSet", "prometheus"): "prometheus",
                  ("StatefulSet", "alertmanager"): "prometheus", ("StatefulSet", "grafana"): "grafana",
                  ("StatefulSet", "elasticsearch"): "elk", ("StatefulSet", "logstash"): "elk",
                  ("Deployment", "kibana"): "elk"}
        owners.update({("ConfigMap", f"bank11-{component}"): component
                       for component in ("zipkin", "prometheus", "grafana", "elk")})
        for mode, sources in self.sources.items():
            for identity, component in owners.items():
                with self.subTest(mode=mode, resource=identity):
                    self.assertTrue(sources[identity].startswith(f"bank/charts/{component}/templates/"), sources[identity])

    def test_dedicated_ha_quorum_and_persistent_single_are_distinct(self):
        single = self.resources(kind="KafkaNodePool")
        self.assertEqual(set(single), {"combined"})
        self.assertEqual(single["combined"]["spec"]["roles"], ["controller", "broker"])
        ha = self.resources(profile="ha", kind="KafkaNodePool")
        self.assertEqual({name: (pool["spec"]["replicas"], pool["spec"]["roles"])
                          for name, pool in ha.items()}, {"controllers": (3, ["controller"]), "brokers": (3, ["broker"])})
        for pool in [*single.values(), *ha.values()]:
            self.assertFalse(pool["spec"]["storage"]["volumes"][0]["deleteClaim"])
        for pool in ha.values():
            spread = pool["spec"]["template"]["pod"]["affinity"]["podAntiAffinity"]["requiredDuringSchedulingIgnoredDuringExecution"]
            self.assertEqual(spread[0]["topologyKey"], "kubernetes.io/hostname")
        kafka = self.resources(profile="ha", kind="Kafka")["bank-kafka"]["spec"]["kafka"]
        self.assertEqual((kafka["config"]["default.replication.factor"], kafka["config"]["min.insync.replicas"]), (3, 2))
        self.assertTrue(kafka["listeners"][0]["tls"])
        self.assertEqual(kafka["listeners"][0]["authentication"]["type"], "scram-sha-512")

    def test_actual_user_acls_bind_each_notification_writer_to_its_source(self):
        users = self.resources(kind="KafkaUser")
        for name in ("accounts", "cash", "transfer"):
            acls = users[f"{name}-service"]["spec"]["authorization"]["acls"]
            writes = {acl["resource"].get("name") for acl in acls if "Write" in acl["operations"]}
            notification_writes = {topic for topic in writes if topic.endswith("notifications.v1")}
            self.assertEqual(notification_writes, {f"bank.{name}.notifications.v1"})
            for acl in acls:
                if acl["resource"]["type"] != "cluster":
                    self.assertEqual(acl["resource"]["patternType"], "literal")
                    self.assertNotIn("*", acl["resource"]["name"])
        notifications = users["notifications-service"]["spec"]["authorization"]["acls"]
        inputs = {acl["resource"].get("name") for acl in notifications if "Read" in acl["operations"] and acl["resource"]["type"] == "topic"}
        self.assertEqual(inputs, {f"bank.{name}.notifications.v1" for name in ("accounts", "cash", "transfer")})
        self.assertNotIn("analytics-service", users)
        topics = {topic["spec"]["topicName"]: topic["spec"] for topic in self.resources(kind="KafkaTopic").values()}
        for name, topic in topics.items():
            if name.endswith(".DLT"):
                source = topics[name.removesuffix(".DLT")]
                self.assertGreaterEqual(topic["partitions"], source["partitions"])
                self.assertEqual(topic["replicas"], source["replicas"])
                self.assertEqual(topic["config"]["min.insync.replicas"], source["config"]["min.insync.replicas"])
                self.assertGreaterEqual(topic["config"]["retention.ms"], 30 * 24 * 60 * 60 * 1000)

    def test_clients_use_native_openbao_and_kafka_pem_without_accounts_oauth_or_notifications_business_http(self):
        deployments = self.resources(kind="Deployment")
        maps = self.resources(kind="ConfigMap")
        services = self.resources(kind="Service")
        self.assertNotIn("notifications", services)
        notification = deployments["notifications"]["spec"]["template"]["spec"]["containers"][0]
        self.assertNotIn("ports", notification)
        self.assertEqual(maps["notifications-client"]["data"]["SPRING_MAIN_WEB_APPLICATION_TYPE"], "none")
        accounts = maps["accounts-client"]["data"]
        self.assertFalse(any(key.startswith("SPRING_SECURITY_OAUTH2_CLIENT") for key in accounts))
        self.assertEqual(accounts["SPRING_KAFKA_SECURITY_PROTOCOL"], "SASL_SSL")
        self.assertEqual(accounts["SPRING_KAFKA_SSL_TRUSTSTORETYPE"], "PEM")
        self.assertEqual(accounts["SPRING_KAFKA_SSL_TRUSTSTORELOCATION"], "file:/run/kafka/ca.crt")
        self.assertEqual(accounts["SPRING_KAFKA_PROPERTIES_SSL_ENDPOINT_IDENTIFICATION_ALGORITHM"], "https")
        pod = deployments["accounts"]["spec"]["template"]["spec"]
        self.assertEqual(pod["serviceAccountName"], "accounts")
        volumes = {volume["name"]: volume for volume in pod["volumes"]}
        self.assertEqual(volumes["openbao-token"]["projected"]["sources"],
                         [{"serviceAccountToken": {"path": "token", "audience": "openbao", "expirationSeconds": 600}}])
        self.assertNotIn("secrets", volumes)
        self.assertIn("vault:///kv/bank11-dev/accounts", accounts["BANK_CONFIG_IMPORT"])
        self.assertEqual(accounts["BANK_VAULT_ROLE"], "bank11-dev-accounts")
        users = self.resources(kind="KafkaUser")
        self.assertEqual(users["accounts-service"]["spec"]["authentication"]["password"]["valueFrom"],
                         {"secretKeyRef": {"name": "bank11-kafka-accounts", "key": "password"}})
        self.assertNotIn("bank11-accounts", self.resources(kind="ExternalSecret"))
        self.assertIn("bank11-kafka-accounts", self.resources(kind="ExternalSecret"))
        self.assertFalse(self.resources(kind="Secret"))

    def test_optional_bi_uses_https_ca_and_keeps_source_storage_when_disabled(self):
        self.assertNotIn("cassandra", self.resources(kind="StatefulSet"))
        stores = self.resources(analytics=True, kind="PersistentVolumeClaim")
        for claim in stores.values():
            self.assertEqual(claim["metadata"]["annotations"]["helm.sh/resource-policy"], "keep")
        self.assertIn("analytics-hot", stores)
        self.assertIn("analytics-cold", stores)
        jobs = self.resources(analytics=True, kind="Job")
        bi = next(job for name, job in jobs.items() if name.startswith("bank-bi-"))
        environment = bi["spec"]["template"]["spec"]["containers"][0]["env"]
        self.assertIn({"name": "BANK_BI_URL", "value": "https://superset:8088"}, environment)
        superset = self.resources(analytics=True, kind="Deployment")["superset"]
        container = superset["spec"]["template"]["spec"]["containers"][0]
        backend_port = container["ports"][0]["containerPort"]
        policies = self.resources(analytics=True, kind="NetworkPolicy")
        self.assertIn(backend_port, {port["port"] for rule in policies["allow-superset-ingress"]["spec"]["ingress"]
                                     for port in rule["ports"]})
        self.assertIn(backend_port, {port["port"] for rule in policies["allow-edge-egress"]["spec"]["egress"]
                                     if any(peer.get("podSelector", {}).get("matchLabels", {}).get("app") == "superset"
                                            for peer in rule["to"]) for port in rule["ports"]})
        self.assertIn({"name": "BANK_BI_AUTH_URL", "value": "https://authorization:8443/realms/bank"}, container["env"])
        self.assertIn({"name": "REQUESTS_CA_BUNDLE", "value": "/run/secrets/internal_ca.pem"}, container["env"])
        secret = self.resources(analytics=True, kind="ExternalSecret")["bank11-superset"]
        self.assertIn("internal_ca.pem", {entry["secretKey"] for entry in secret["spec"]["data"]})
        self.assertIn({"name": "BANK_BI_PUBLIC_AUTH_URL", "value": "https://auth-bank.valgrind-home.localhost:8460/realms/bank"},
                      superset["spec"]["template"]["spec"]["containers"][0]["env"])
        analytics = self.resources(analytics=True, kind="KafkaUser")["analytics-service"]["spec"]["authorization"]["acls"]
        groups = {acl["resource"].get("name") for acl in analytics if acl["resource"]["type"] == "group"}
        self.assertEqual(groups, {"bank.analytics.accounts.ingress.v1", "bank.analytics.personal.v1", "bank.analytics.business.v1", "bank.analytics.failures.v1"})
        for deployment in self.resources(analytics=True, kind="Deployment").values():
            self.assertEqual(deployment["spec"]["replicas"], 1)
            self.assertEqual(deployment["spec"]["strategy"]["type"], "Recreate")

        values_path = Path(self.directory.name) / "single-True/public-values.yaml"
        baseline_values = yaml.safe_load(values_path.read_text(encoding="utf-8"))
        baseline = {(item["kind"], item["metadata"]["name"]): item["spec"]["template"]
                    for item in self.models[("single", True)]
                    if item and item["kind"] in {"Deployment", "StatefulSet"}}
        changes = (
            ("bank11-edge", "Caddyfile", {("Deployment", "edge")}),
            ("bank11-setup", "start-database.sh", {("StatefulSet", "database")}),
            ("bank11-database", "init-database.sh", {("StatefulSet", "database")}),
            ("bank11-authorization", "bank-realm.json", {("Deployment", "authorization")}),
            ("bank11-analytics-setup", "start-cassandra.sh", {("StatefulSet", "cassandra")}),
            ("bank11-analytics-setup", "probe-cassandra.py", {("StatefulSet", "cassandra")}),
            ("bank11-analytics-setup", "start-clickhouse.sh", {("StatefulSet", "clickhouse")}),
            ("bank11-analytics-setup", "clickhouse-users.xml", {("StatefulSet", "clickhouse")}),
            ("bank11-analytics-setup", "clickhouse-storage.xml", {("StatefulSet", "clickhouse")}),
            ("bank11-setup", "init-config.sh", {("Deployment", name) for name in
             ("accounts", "cash", "transfer", "notifications", "gateway", "front", "analytics")}),
        )
        for config_map, asset, expected in changes:
            with self.subTest(config_map=config_map, asset=asset):
                values = copy.deepcopy(baseline_values)
                values["assets"][config_map][asset] += "\n"
                override = Path(self.directory.name) / "changed-asset.yaml"
                override.write_text(yaml.safe_dump(values), encoding="utf-8")
                rendered = subprocess.run(["helm", "template", "bank", str(self.project / "deployment/charts/bank"),
                                           "--namespace", "bank11-dev", "-f", str(override)],
                                          capture_output=True, text=True, check=True)
                actual = {(item["kind"], item["metadata"]["name"]): item["spec"]["template"]
                          for item in yaml.safe_load_all(rendered.stdout)
                          if item and item["kind"] in {"Deployment", "StatefulSet"}}
                self.assertEqual({key for key in baseline if baseline[key] != actual[key]}, expected)

    def test_bi_initializer_replacement_tracks_both_images_and_chart(self):
        """Обновление init-образа не должно патчить неизменяемый Pod template существующего Job."""
        values_path = Path(self.directory.name) / "single-True/public-values.yaml"
        baseline = yaml.safe_load(values_path.read_text(encoding="utf-8"))
        previous = next(name for name in self.resources(analytics=True, kind="Job") if name.startswith("bank-bi-"))
        for scope, key, value in (("superset", "image", "bank11-superset:updated"),
                                  ("superset", "provisionerImage", "bank11-bank-bi:updated"),
                                  ("global", "chartRevision", "f" * 64)):
            with self.subTest(scope=scope, key=key):
                updated = copy.deepcopy(baseline)
                updated[scope][key] = value
                override = Path(self.directory.name) / "updated-bi.yaml"
                override.write_text(yaml.safe_dump(updated), encoding="utf-8")
                rendered = subprocess.run(["helm", "template", "bank", str(self.project / "deployment/charts/bank"),
                                           "--namespace", "bank11-dev", "-f", str(override)],
                                          capture_output=True, text=True, check=True)
                current = next(item["metadata"]["name"] for item in yaml.safe_load_all(rendered.stdout)
                               if item and item["kind"] == "Job" and item["metadata"]["name"].startswith("bank-bi-"))
                self.assertNotEqual(previous, current)

    def test_foreign_namespace_fails_before_any_cluster_contact(self):
        result = subprocess.run(["helm", "template", "bank", str(self.project / "deployment/charts/bank"), "--namespace", "default",
                                 "-f", str(Path(self.directory.name) / "single-False/public-values.yaml")], capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("release namespace must equal", result.stderr)
        extra_replica = subprocess.run(["helm", "template", "bank", str(self.project / "deployment/charts/bank"), "--namespace", "bank11-dev",
                                       "-f", str(Path(self.directory.name) / "single-False/public-values.yaml"),
                                       "--set", "apps.accounts.replicas=2"], capture_output=True, text=True)
        self.assertNotEqual(extra_replica.returncode, 0)
        self.assertIn("replicas", extra_replica.stderr)

    def test_long_running_workloads_have_application_level_lifecycle_probes(self):
        for profile, analytics in self.models:
            workloads = {**self.resources(profile, analytics, kind="Deployment"),
                         **self.resources(profile, analytics, kind="StatefulSet")}
            for name, workload in workloads.items():
                with self.subTest(profile=profile, analytics=analytics, workload=name):
                    container = workload["spec"]["template"]["spec"]["containers"][0]
                    for probe in ("startupProbe", "readinessProbe", "livenessProbe"):
                        self.assertIn(probe, container)
                        self.assertGreater(container[probe].get("timeoutSeconds", 1), 0)
                    self.assertNotIn("tcpSocket", container["readinessProbe"])
            applications = self.resources(profile, analytics, kind="Deployment")
            for name in ("accounts", "cash", "transfer", "front", "gateway", "analytics"):
                if name in applications:
                    container = applications[name]["spec"]["template"]["spec"]["containers"][0]
                    self.assertEqual(container["readinessProbe"]["httpGet"]["path"],
                                     "/actuator/health/readiness")
                    self.assertEqual(container["livenessProbe"]["httpGet"]["path"],
                                     "/actuator/health/liveness")
            worker = applications["notifications"]["spec"]["template"]["spec"]["containers"][0]
            self.assertIn("exec", worker["readinessProbe"])
            self.assertNotIn("ports", worker)


if __name__ == "__main__":
    unittest.main()
