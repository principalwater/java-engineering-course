"""Exercise owned cluster lifecycle at the CLI/file boundary without Docker mutations."""

import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import yaml

import runtime


class ClusterCreationTest(unittest.TestCase):
    def test_stop_preserves_single_node_scheduling_and_rejects_unrecorded_nodes(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            name = "bank10-dev-single-control-plane"
            identity = {"nodes": {name: "c" * 64}}
            arguments = argparse.Namespace(state_dir=state)
            mutations = []
            foreign_node = False

            def fake_kubectl(cluster, *argv, **kwargs):
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
                                             ("delete", "bank10.io/role=application"),
                                             ("delete", "bank10.io/role=storage"),
                                             ("delete", "bank10.io/role=kafka"),
                                             ("docker", "stop", "--time", "60", name)])
                mutations.clear()
                foreign_node = True
                with self.assertRaisesRegex(ValueError, "node inventory"):
                    runtime.lifecycle(arguments, stop=True)
                self.assertFalse(mutations)

    def test_failed_bootstrap_reuses_exact_network_receipt_and_encryption_key(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / "state"
            secret_source = Path(directory) / "private"
            secret_source.mkdir(mode=0o700)
            arguments = argparse.Namespace(state_dir=state, secrets_dir=secret_source, environment="test",
                                           profile="ha", external_port=8450)
            cluster = "bank10-test-ha"
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
                                 "Labels": {runtime.OWNER_LABEL: cluster}, "IPAM": {"Config": [{"Subnet": "10.251.10.160/27"}]}}
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
                self.assertTrue(all(node["labels"]["bank10.io/worker"] == "true" for node in topology["nodes"]))
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


if __name__ == "__main__":
    unittest.main()
