"""Exercise owned cluster lifecycle at the CLI/file boundary without Docker mutations."""

import argparse
import base64
from contextlib import redirect_stderr
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import yaml

import probe
import runtime


class ClusterCreationTest(unittest.TestCase):
    def test_helm_test_uses_only_the_verified_release_identity(self):
        identity = {"kubeconfig": "/private/kubeconfig", "context": "kind-bank11-test-single", "namespace": "bank11-test"}
        with patch("sys.argv", ["runtime.py", "--state-dir", "/private/state", "test"]), \
                patch.object(runtime, "owned_identity", return_value=identity) as owned, \
                patch.object(runtime, "command", return_value="PASS") as command:
            runtime.main()
        owned.assert_called_once_with(Path("/private/state"))
        command.assert_called_once_with(["helm", "--kubeconfig", identity["kubeconfig"], "--kube-context", identity["context"],
                                         "--namespace", identity["namespace"], "test", "bank", "--timeout", "180s"], timeout=210)

    def test_failed_helm_hook_reports_only_safe_phase_markers(self):
        identity = {"kubeconfig": "/private/kubeconfig", "context": "kind-bank11-test-single", "namespace": "bank11-test"}
        output = "BANK_HELM_TEST readiness accounts\nBANK_HELM_TEST failed readiness accounts exit=28\npassword=private\nBANK_HELM_TEST kafka private\n"
        diagnostics = io.StringIO()
        with patch("sys.argv", ["runtime.py", "--state-dir", "/private/state", "test"]), \
                patch.object(runtime, "owned_identity", return_value=identity), \
                patch.object(runtime, "command", side_effect=RuntimeError("Command failed")), \
                patch.object(runtime, "kubectl", return_value=output), redirect_stderr(diagnostics):
            with self.assertRaisesRegex(RuntimeError, "Command failed"):
                runtime.main()
        self.assertEqual(diagnostics.getvalue(),
                         "BANK_HELM_TEST readiness accounts\nBANK_HELM_TEST failed readiness accounts exit=28\n")

    def test_image_operations_refuse_low_host_space_before_each_build_and_import(self):
        floor = 5 * 1024 ** 3
        cases = (([floor - 1], []),
                 ([floor, floor - 1], [("docker", "build")]),
                 ([floor, floor, floor - 1], [("docker", "build"), ("kind", "load")]))
        for available, expected in cases:
            with self.subTest(available=available), tempfile.TemporaryDirectory() as directory:
                state = Path(directory)
                (state / "source.json").write_text(json.dumps({"analytics": False, "superset": False}))
                mutations = []

                def fake_command(argv, **kwargs):
                    mutations.append(tuple(argv[:2]))
                    if len(mutations) > len(expected):
                        self.fail("Image operation ran below the required free host disk floor")
                    return ""

                with patch.object(runtime, "owned_identity", return_value={"cluster": "bank11-test-ha"}), \
                        patch.object(runtime, "image_reference", return_value="bank11-accounts:" + "a" * 20), \
                        patch.object(shutil, "disk_usage", side_effect=[SimpleNamespace(free=value) for value in available]), \
                        patch.object(runtime, "command", side_effect=fake_command):
                    with self.assertRaisesRegex(ValueError, "free host disk"):
                        runtime.load_images(argparse.Namespace(state_dir=state))
                self.assertEqual(mutations, expected)
                self.assertFalse((state / "images.json").exists())

    def test_stop_preserves_single_node_scheduling_and_rejects_unrecorded_nodes(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            name = "bank11-dev-single-control-plane"
            identity = {"nodes": {name: "c" * 64}}
            arguments = argparse.Namespace(state_dir=state)
            mutations = []
            foreign_node = False

            def fake_kubectl(cluster, *argv, **kwargs):
                if argv[:2] == ("get", "namespace"):
                    return ""
                if argv[:2] == ("get", "nodes"):
                    node = {"kind": "Node", "metadata": {"name": name}, "spec": {"unschedulable": True}}
                    if name in argv[2:]:
                        return json.dumps(node)
                    nodes = [node]
                    if foreign_node:
                        nodes.append({"kind": "Node", "metadata": {"name": "unrecorded-worker"}})
                    return json.dumps({"kind": "List", "items": nodes})
                mutations.append(argv[:2] if argv[0] == "cordon" else (argv[0], argv[3]))
                return ""

            def fake_command(argv, **kwargs):
                mutations.append(tuple(argv))
                return ""

            with patch.object(runtime, "owned_identity", return_value=identity), \
                    patch.object(runtime, "kubectl", side_effect=fake_kubectl), \
                    patch.object(runtime, "command", side_effect=fake_command):
                runtime.lifecycle(arguments, stop=True)
                self.assertEqual(json.loads((state / "scheduling.json").read_text()), {name: True})
                self.assertEqual(mutations, [("cordon", name),
                                             ("delete", "bank11.io/role=application"),
                                             ("delete", "bank11.io/role=observability"),
                                             ("delete", "bank11.io/role=storage"),
                                             ("delete", "bank11.io/role=kafka"),
                                             ("docker", "update", "--restart=unless-stopped", name),
                                             ("docker", "stop", "--time", "60", name)])
                mutations.clear()
                foreign_node = True
                with self.assertRaisesRegex(ValueError, "node inventory"):
                    runtime.lifecycle(arguments, stop=True)
                self.assertFalse(mutations)

    def test_resume_refuses_api_readiness_deadline_before_restoring_scheduling(self):
        # Не готовый после cold start API не должен пропускать uncordon и работу с OpenBao.
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            kubeconfig = state / "kubeconfig"
            kubeconfig.touch()
            node = "bank11-dev-single-control-plane"
            identity = {"cluster": "bank11-dev-single", "namespace": "bank11-dev",
                        "kubeconfig": str(kubeconfig), "context": "kind-bank11-dev-single",
                        "nodes": {node: "a" * 64}}
            scheduling = state / "scheduling.json"
            scheduling.write_text(json.dumps({node: False}))
            (state / "creation.json").write_text(json.dumps({"secrets_dir": str(state / "source")}))
            with patch.object(runtime, "owned_identity", return_value=identity), \
                    patch.object(runtime, "command"), patch.object(runtime, "kubectl", return_value="") as kubectl, \
                    patch.object(runtime.subprocess, "run", return_value=subprocess.CompletedProcess([], 1)), \
                    patch.object(runtime.time, "monotonic", side_effect=[0, 1, 180]), \
                    patch.object(runtime.time, "sleep"):
                with self.assertRaisesRegex(RuntimeError, "API readiness timed out"):
                    runtime.lifecycle(argparse.Namespace(state_dir=state), stop=False)
            kubectl.assert_not_called()
            self.assertEqual(json.loads(scheduling.read_text()), {node: False})

    def test_resume_distinguishes_partial_bootstrap_from_missing_initialized_openbao(self):
        import bao
        for installed, recovery in ((False, False), (False, True), (True, True)):
            with self.subTest(installed=installed, recovery=recovery), tempfile.TemporaryDirectory() as directory:
                state = Path(directory)
                kubeconfig = state / "kubeconfig"
                kubeconfig.touch()
                secret_dir = state / "source"
                secret_dir.mkdir()
                identity = {"cluster": "bank11-dev-single", "namespace": "bank11-dev",
                            "kubeconfig": str(kubeconfig), "context": "kind-bank11-dev-single",
                            "nodes": {"bank11-dev-single-control-plane": "a" * 64}}
                (state / "creation.json").write_text(json.dumps({"secrets_dir": str(secret_dir)}))
                if recovery:
                    path = bao.recovery_path(secret_dir, identity["cluster"])
                    path.parent.mkdir(parents=True)
                    path.write_text("{}")

                def fake_kubectl(cluster, *argv, **kwargs):
                    if argv[:2] == ("get", "namespace"):
                        return json.dumps({"metadata": {"labels": {runtime.OWNER_LABEL: identity["cluster"]}}})
                    if argv[:2] == ("get", "statefulset"):
                        return json.dumps({"metadata": {"name": "openbao"}}) if installed else ""
                    self.fail(f"Unexpected Kubernetes operation: {argv[:2]}")

                with patch.object(runtime, "owned_identity", return_value=identity), \
                        patch.object(runtime, "command"), patch.object(runtime, "kubectl", side_effect=fake_kubectl), \
                        patch.object(runtime.subprocess, "run", return_value=subprocess.CompletedProcess([], 0)), \
                        patch.object(bao, "bootstrap") as bootstrap:
                    if recovery and not installed:
                        with self.assertRaisesRegex(ValueError, "explicit reconciliation"):
                            runtime.lifecycle(argparse.Namespace(state_dir=state), stop=False)
                    else:
                        runtime.lifecycle(argparse.Namespace(state_dir=state), stop=False)
                    self.assertEqual(bootstrap.call_count, int(installed))
                    if installed:
                        self.assertIs(bootstrap.call_args.kwargs["may_initialize"], False)

    def test_failed_bootstrap_reuses_exact_network_receipt_and_encryption_key(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / "state"
            secret_source = Path(directory) / "private"
            secret_source.mkdir(mode=0o700)
            arguments = argparse.Namespace(state_dir=state, secrets_dir=secret_source, environment="test",
                                           profile="ha", external_port=8460)
            cluster = "bank11-test-ha"
            node_names = [cluster + suffix for suffix in ("-control-plane", "-worker", "-worker2")]
            node_ids = {name: str(index + 1) * 64 for index, name in enumerate(node_names)}
            created_networks = []
            node_present = False
            network_id = "a" * 64

            def fake_command(argv, **kwargs):
                if argv[:3] == ["kind", "get", "clusters"]:
                    return ""
                if argv[:4] == ["docker", "network", "ls", "-q"]:
                    return "none\n" + (network_id + "\n" if created_networks else "")
                if argv[:3] == ["docker", "network", "inspect"]:
                    model = {"Name": "none", "IPAM": {"Config": None}}
                    if argv[3] == network_id:
                        model = {"Name": cluster, "Id": network_id,
                                 "Labels": {runtime.OWNER_LABEL: cluster}, "IPAM": {"Config": [{"Subnet": "10.251.11.160/27"}]}}
                    return json.dumps([model])
                if argv[:3] == ["docker", "network", "create"]:
                    created_networks.append(network_id)
                    return network_id
                if argv[:3] == ["docker", "ps", "-a"]:
                    return "\n".join(node_names) if node_present else ""
                if argv[:2] == ["docker", "inspect"]:
                    return json.dumps({"owned": {"NetworkID": network_id}}) if "Networks" in argv[3] else node_ids[argv[-1]]
                if argv[0] == "kubectl":
                    return "owned-kube-system-uid"
                self.fail(f"Unexpected external command: {argv[:3]}")

            attempts = 0

            def fake_kind(argv, **kwargs):
                nonlocal attempts, node_present
                attempts += 1
                if attempts == 1:
                    return subprocess.CompletedProcess(argv, 1, b"", b"ERROR: controlled bootstrap failure")
                node_present = True
                config = Path(argv[argv.index("--kubeconfig") + 1])
                config.write_text("private test fixture", encoding="utf-8")
                return subprocess.CompletedProcess(argv, 0, b"", b"")

            with patch.dict(os.environ, BANK_ALLOW_PLAINTEXT_TEST_SECRETS="true"), patch.object(runtime.sys, "platform", "linux"), \
                    patch.object(runtime, "command", side_effect=fake_command), patch.object(runtime.subprocess, "run", side_effect=fake_kind):
                with self.assertRaisesRegex(RuntimeError, "exact owned resources retained"):
                    runtime.create_cluster(arguments)
                intent = json.loads((state / "creation.json").read_text(encoding="utf-8"))
                self.assertEqual(intent["network_id"], network_id)
                topology = yaml.safe_load((state / "kind.yaml").read_text())
                self.assertEqual([node["role"] for node in topology["nodes"]], ["control-plane", "worker", "worker"])
                self.assertTrue(all(node["labels"]["bank11.io/worker"] == "true" for node in topology["nodes"]))
                control_plane_patch = yaml.safe_load(topology["nodes"][0]["kubeadmConfigPatches"][0])
                self.assertEqual(control_plane_patch, {"kind": "InitConfiguration", "nodeRegistration": {"taints": []}})
                key_file = secret_source / ("kubernetes/" + cluster + "/encryption.yaml")
                encrypted_config = key_file.read_bytes()
                runtime.create_cluster(arguments)
                self.assertEqual(created_networks, [network_id])
                self.assertEqual(key_file.read_bytes(), encrypted_config)
                identity = json.loads((state / "cluster.json").read_text(encoding="utf-8"))
                self.assertEqual(identity["nodes"], node_ids)
                self.assertEqual(identity["network_id"], network_id)


class KafkaProbeTest(unittest.TestCase):
    def test_retention_rejects_partial_or_duplicate_cli_output(self):
        for received, expected_success in (("new\n", False), ("new\nnew\n", False), ("old\nnew\n", True)):
            with self.subTest(received=received), tempfile.TemporaryDirectory() as directory:
                result = None

                def run_script(identity, user, body):
                    nonlocal result
                    fixture = '''bootstrap=fixture
function timeout() {
    for option in "$@"; do
        case "$option" in
            --consumer.config|--consumer-property) printf '%s\\n' 'Option is deprecated' ;;
        esac
    done
    printf "%s" "$RECORDS"
}
'''
                    script = fixture + body.replace("/tmp/", directory + "/")
                    result = subprocess.run(["bash", "-euo", "pipefail", "-c", script],
                                            env={**os.environ, "RECORDS": received}, capture_output=True, text=True)

                with patch.object(probe, "job", side_effect=run_script):
                    probe.probe_records({}, "fixture-user", "fixture-topic", "fixture-group", ("old", "new"), publish=False)
                self.assertEqual(result.returncode == 0, expected_success)

    def test_failure_reports_current_stage_instead_of_prior_expected_denial(self):
        with tempfile.TemporaryDirectory() as directory:
            identity = {"kubeconfig": str(Path(directory) / "kubeconfig")}
            created = None

            def fake_kubectl(cluster, *argv, **kwargs):
                nonlocal created
                if argv[:2] == ("get", "service"):
                    return json.dumps({"spec": {"clusterIP": "10.96.0.10"}})
                if argv[0] == "create":
                    created = json.loads(kwargs["data"])
                    created["metadata"]["uid"] = "owned-probe-fixture"
                    return json.dumps(created)
                if argv[:2] == ("get", "job"):
                    return json.dumps({**created, "status": {"failed": 1}})
                if argv[0] == "logs":
                    return "TopicAuthorizationException\nBANK_PROBE_FAILURE hostname-mismatch 1 TimeoutException\nprivate diagnostic detail\n"
                if argv[:2] == ("get", "secret"):
                    return json.dumps({"data": {"password": base64.b64encode(b"private-test-password").decode()}})
                if argv[:2] == ("delete", "job"):
                    return ""
                self.fail(f"Unexpected probe boundary command: {argv[:2]}")

            with patch.object(probe, "kubectl", side_effect=fake_kubectl):
                with self.assertRaisesRegex(RuntimeError, "phase=hostname-mismatch, exit=1") as failure:
                    probe.job(identity, "accounts-service", "false")
            self.assertNotIn("TopicAuthorizationException", str(failure.exception))
            self.assertIn("category=TimeoutException", str(failure.exception))
            self.assertNotIn("private diagnostic detail", str(failure.exception))


if __name__ == "__main__":
    unittest.main()
