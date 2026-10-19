"""Exercise resumable creation at the actual CLI/file boundary without Docker mutations."""

import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import runtime


class ClusterCreationTest(unittest.TestCase):
    def test_failed_bootstrap_reuses_exact_network_receipt_and_encryption_key(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / "state"
            secret_source = Path(directory) / "private"
            secret_source.mkdir(mode=0o700)
            arguments = argparse.Namespace(state_dir=state, secrets_dir=secret_source, environment="dev",
                                           profile="single", external_port=8450)
            created_networks = []
            node_present = False
            node_id = "c" * 64
            network_id = "a" * 64

            def fake_command(argv, **kwargs):
                if argv[:3] == ["kind", "get", "clusters"]:
                    return ""
                if argv[:4] == ["docker", "network", "ls", "-q"]:
                    return "none\n" + (network_id + "\n" if created_networks else "")
                if argv[:3] == ["docker", "network", "inspect"]:
                    model = {"Name": "none", "IPAM": {"Config": None}}
                    if argv[3] == network_id:
                        model = {"Name": "bank10-dev-single", "Id": network_id,
                                 "Labels": {runtime.OWNER_LABEL: "bank10-dev-single"}, "IPAM": {"Config": [{"Subnet": "10.251.10.64/27"}]}}
                    return json.dumps([model])
                if argv[:3] == ["docker", "network", "create"]:
                    created_networks.append(network_id)
                    return network_id
                if argv[:3] == ["docker", "ps", "-a"]:
                    return "bank10-dev-single-control-plane" if node_present else ""
                if argv[:2] == ["docker", "inspect"]:
                    return json.dumps({"owned": {"NetworkID": network_id}}) if "Networks" in argv[3] else node_id
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
                key_file = secret_source / "kubernetes/bank10-dev-single/encryption.yaml"
                encrypted_config = key_file.read_bytes()
                runtime.create_cluster(arguments)
                self.assertEqual(created_networks, [network_id])
                self.assertEqual(key_file.read_bytes(), encrypted_config)
                identity = json.loads((state / "cluster.json").read_text(encoding="utf-8"))
                self.assertEqual(identity["nodes"], {"bank10-dev-single-control-plane": node_id})
                self.assertEqual(identity["network_id"], network_id)


if __name__ == "__main__":
    unittest.main()
