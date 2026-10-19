"""Protect saved-plan safety and exact secret redaction at the CLI owner boundary."""

import base64
import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest


SPEC = importlib.util.spec_from_file_location("bank_helm_deploy", Path(__file__).parents[1] / "deploy.py")
DEPLOY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DEPLOY)


class PlanGuardTest(unittest.TestCase):
    def test_review_accepts_only_nondestructive_bank_release_actions(self):
        changes = [{"address": "helm_release.bank", "change": {"actions": ["update"]}},
                   {"address": "helm_release.strimzi", "change": {"actions": ["create"]}}]
        self.assertEqual(DEPLOY.validate_plan({"resource_changes": changes}), {"update": 1, "create": 1})
        for address, actions in (("helm_release.foreign", ["no-op"]), ("helm_release.bank", ["delete", "create"])):
            with self.subTest(address=address, actions=actions), self.assertRaises(ValueError):
                DEPLOY.validate_plan({"resource_changes": [{"address": address, "change": {"actions": actions}}]})

    def test_multiline_and_encoded_credentials_are_rejected_without_echo(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "tls_private_key.pem"
            secret = b"-----BEGIN PRIVATE KEY-----\nexample-private-material\n-----END PRIVATE KEY-----\n"
            path.write_bytes(secret)
            path.chmod(0o600)
            response = {"items": [{"apiVersion": "v1", "kind": "Secret", "type": "Opaque",
                                   "metadata": {"name": "bank-kafka-cluster-ca", "labels": {"strimzi.io/cluster": "bank-kafka"}},
                                   "data": {"ca.key": base64.b64encode(secret).decode()}}]}
            kubernetes = SimpleNamespace(kubectl=lambda *args: json.dumps(response))
            sources = {"file": DEPLOY.secret_variants(Path(directory), [path.name]),
                       "Strimzi Secret": DEPLOY.generated_secret_variants(kubernetes, {})}
            for source, variants in sources.items():
                for value in (secret, secret.strip(), base64.b64encode(secret),
                              json.dumps(secret.decode())[1:-1].encode(),
                              json.dumps(secret.decode().strip())[1:-1].encode()):
                    with self.subTest(source=source, encoding=len(value)):
                        with self.assertRaises(ValueError) as failure:
                            DEPLOY.require_no_secrets(b"prefix " + value + b" suffix", variants, "saved plan")
                        self.assertNotIn("example-private-material", str(failure.exception))
                        self.assertEqual(DEPLOY.redact(value, variants), b"[redacted]")


if __name__ == "__main__":
    unittest.main()
