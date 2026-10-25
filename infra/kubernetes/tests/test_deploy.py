"""Protect saved-plan safety and exact secret redaction at the CLI owner boundary."""

import base64
from contextlib import redirect_stdout
import importlib.util
import io
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("bank_helm_deploy", Path(__file__).parents[1] / "deploy.py")
DEPLOY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DEPLOY)


class PlanGuardTest(unittest.TestCase):
    def test_failed_phase_reports_current_fixed_category_without_private_output(self):
        cases = (("plan", b"context deadline exceeded", "deadline"),
                 ("apply", b"BackoffLimitExceeded", "job-failed"),
                 ("init", b"failed to download chart", "chart-fetch"),
                 ("show", b"unclassified failure", "other"))
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "tofu.log"
            for phase, message, category in cases:
                with self.subTest(phase=phase):
                    log.write_text("old connection refused", encoding="utf-8")
                    response = SimpleNamespace(returncode=1, stdout=message + b" private-value")
                    output = io.StringIO()
                    with patch.object(DEPLOY.subprocess, "run", return_value=response), redirect_stdout(output):
                        with self.assertRaisesRegex(RuntimeError, "phase failed") as failure:
                            DEPLOY.run(["tofu", "-chdir=/public/infra", phase], environment={},
                                       values=[b"private-value"], log=log)
                    self.assertEqual(output.getvalue(), f"BANK_CI_TOFU {category} {phase}\n")
                    self.assertNotIn("private-value", str(failure.exception))
                    self.assertNotIn(b"private-value", log.read_bytes())

    def test_review_accepts_only_nondestructive_bank_release_actions(self):
        changes = [{"address": "helm_release.bank", "change": {"actions": ["update"]}},
                   {"address": "helm_release.strimzi", "change": {"actions": ["create"]}}]
        self.assertEqual(DEPLOY.validate_plan({"resource_changes": changes}), {"update": 1, "create": 1})
        for address, actions in (("helm_release.foreign", ["no-op"]), ("helm_release.bank", ["delete", "create"])):
            with self.subTest(address=address, actions=actions), self.assertRaises(ValueError):
                DEPLOY.validate_plan({"resource_changes": [{"address": address, "change": {"actions": actions}}]})
        network = {"resource_changes": [{"address": "helm_release.cilium[0]", "change": {"actions": ["create"]}}]}
        with self.assertRaises(ValueError):
            DEPLOY.validate_plan(network)
        self.assertEqual(DEPLOY.validate_plan(network, operator_only=True, dual_stack=True), {"create": 1})
        secrets = {"resource_changes": [{"address": "helm_release.openbao[0]", "change": {"actions": ["create"]}}]}
        self.assertEqual(DEPLOY.validate_plan(secrets, secrets_only=True, dual_stack=True), {"create": 1})
        with self.assertRaises(ValueError):
            DEPLOY.validate_plan(secrets, operator_only=True, dual_stack=True)
        with self.assertRaises(ValueError):
            DEPLOY.validate_plan({"resource_changes": changes}, secrets_only=True, dual_stack=True)

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

    def test_telegram_identifier_exception_does_not_weaken_password_guard(self):
        with tempfile.TemporaryDirectory() as directory:
            for name in ("telegram_chat_id", "password"):
                path = Path(directory) / name
                path.write_bytes(b"-123456789\n")
                path.chmod(0o600)
            self.assertTrue(DEPLOY.secret_variants(Path(directory), ["telegram_chat_id"]))
            with self.assertRaises(ValueError):
                DEPLOY.secret_variants(Path(directory), ["password"])


if __name__ == "__main__":
    unittest.main()
