"""Test rendered deployment contracts before any Kubernetes lifecycle operation."""

from pathlib import Path
import subprocess
import tempfile
import unittest

import yaml

from prepare import prepare, project_dir


class BankChartTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory(prefix="bank10-chart-")
        cls.project = project_dir()
        cls.models = {}
        for profile, analytics, superset in (("single", False, False), ("single", True, True), ("ha", False, False)):
            destination = Path(cls.directory.name) / f"{profile}-{analytics}"
            prepare(destination, environment="dev", profile=profile, analytics=analytics,
                    superset=superset, external_port=8450)
            rendered = subprocess.run(["helm", "template", "bank", str(cls.project / "charts/bank"),
                                       "--namespace", "bank10-dev", "-f", str(destination / "public-values.yaml")],
                                      capture_output=True, text=True, check=True)
            cls.models[(profile, analytics)] = list(yaml.safe_load_all(rendered.stdout))

    @classmethod
    def tearDownClass(cls):
        cls.directory.cleanup()

    def resources(self, profile="single", analytics=False, kind=None):
        objects = self.models[(profile, analytics)]
        return {item["metadata"]["name"]: item for item in objects if item and item["kind"] == kind}

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

    def test_clients_use_operator_password_and_pem_without_accounts_oauth_or_notifications_http(self):
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
        sources = deployments["accounts"]["spec"]["template"]["spec"]["volumes"][1]["projected"]["sources"]
        self.assertIn({"secret": {"name": "accounts-service", "items": [{"key": "password", "path": "bank.kafka.password"}]}}, sources)
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
        self.assertIn({"name": "BANK_BI_PUBLIC_AUTH_URL", "value": "https://auth-bank.valgrind-home.localhost:8450/realms/bank"},
                      superset["spec"]["template"]["spec"]["containers"][0]["env"])
        analytics = self.resources(analytics=True, kind="KafkaUser")["analytics-service"]["spec"]["authorization"]["acls"]
        groups = {acl["resource"].get("name") for acl in analytics if acl["resource"]["type"] == "group"}
        self.assertEqual(groups, {"bank.analytics.accounts.ingress.v1", "bank.analytics.personal.v1", "bank.analytics.business.v1"})
        for deployment in self.resources(analytics=True, kind="Deployment").values():
            self.assertEqual(deployment["spec"]["replicas"], 1)
            self.assertEqual(deployment["spec"]["strategy"]["type"], "Recreate")

    def test_foreign_namespace_fails_before_any_cluster_contact(self):
        result = subprocess.run(["helm", "template", "bank", str(self.project / "charts/bank"), "--namespace", "default",
                                 "-f", str(Path(self.directory.name) / "single-False/public-values.yaml")], capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("release namespace must equal", result.stderr)
        extra_replica = subprocess.run(["helm", "template", "bank", str(self.project / "charts/bank"), "--namespace", "bank10-dev",
                                       "-f", str(Path(self.directory.name) / "single-False/public-values.yaml"),
                                       "--set", "apps.accounts.replicas=2"], capture_output=True, text=True)
        self.assertNotEqual(extra_replica.returncode, 0)
        self.assertIn("replicas", extra_replica.stderr)


if __name__ == "__main__":
    unittest.main()
