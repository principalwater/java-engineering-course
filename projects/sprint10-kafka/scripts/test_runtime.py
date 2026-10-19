"""Контракты разбора собственной формы и границы безопасного runtime-клиента."""

import importlib.util
from contextlib import redirect_stderr, redirect_stdout
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch
from urllib.parse import urlencode
from urllib.request import Request


specification = importlib.util.spec_from_file_location("verify_runtime", Path(__file__).with_name("verify-runtime.py"))
runtime = importlib.util.module_from_spec(specification)
specification.loader.exec_module(runtime)


class RuntimeFormTest(unittest.TestCase):
    def test_actual_realm_supplies_stable_access_subject_and_registered_logout(self):
        realm = json.loads(Path(__file__).resolve().parents[1].joinpath("authorization/bank-realm.json").read_text())
        front = next(client for client in realm["clients"] if client["clientId"] == "bank-front")
        basic = next((scope for scope in realm["clientScopes"] if scope["name"] == "basic"), None)
        self.assertIsNotNone(basic, "Keycloak26 access tokens require an explicit subject mapper")
        self.assertIn("basic", front["defaultClientScopes"])
        self.assertTrue(any(mapper["protocolMapper"] == "oidc-sub-mapper" and mapper["config"].get("access.token.claim") == "true"
                            for mapper in basic["protocolMappers"]))
        self.assertEqual("https://bank.valgrind-home.localhost:8450/login?logout", front["attributes"]["post.logout.redirect.uris"])

    def test_selects_only_login_form_and_preserves_hidden_input(self):
        form = runtime.LoginForm()
        form.feed('<form id="other" action="/wrong"><input type="hidden" name="wrong" value="bad"></form>'
                  '<form id="kc-form-login" action="/login-actions/authenticate?a=1&amp;b=2">'
                  '<input type="hidden" name="credentialId" value="fixture"><input name="password" value="unused"></form>')
        self.assertEqual("/login-actions/authenticate?a=1&b=2", form.action)
        self.assertEqual({"credentialId": "fixture"}, form.hidden)
        self.assertEqual({}, form.fields)

    def test_profile_form_preserves_names_without_copying_unrelated_fields(self):
        form = runtime.LoginForm("kc-update-profile-form")
        form.feed('<form id="kc-update-profile-form" action="/login-actions/required-action">'
                  '<input name="firstName" value="Alice"><input name="lastName" value="Example">'
                  '<input name="email" value=""><input name="unrelated" value="discard"></form>')
        self.assertEqual({"firstName": "Alice", "lastName": "Example", "email": ""}, form.fields)

    def test_rejects_duplicate_login_forms(self):
        form = runtime.LoginForm()
        with self.assertRaises(runtime.VerificationError):
            form.feed('<form id="kc-form-login" action="/one"></form><form id="kc-form-login" action="/two"></form>')

    def test_callback_guard_rejects_http_or_foreign_origins(self):
        base = "https://bank.valgrind-home.localhost:8450"
        guard = runtime.SafeRedirect(base)
        for callback in ("http://bank.valgrind-home.localhost:8450/login/oauth2/code/bank", "https://evil.example/callback"):
            target = "https://auth-bank.valgrind-home.localhost:8450/realms/bank/protocol/openid-connect/auth?" + urlencode({"redirect_uri": callback})
            with self.assertRaises(runtime.VerificationError):
                guard.redirect_request(Request(base), None, 302, "Found", {}, target)
        self.assertEqual(0, guard.authorization_redirects)

    def test_target_and_uuid_boundaries_reject_credentials_and_sql_fragments(self):
        self.assertFalse(runtime.allowed_url("https://alice:secret@bank.valgrind-home.localhost:8450"))
        self.assertFalse(runtime.allowed_url("http://bank.valgrind-home.localhost:8450"))
        self.assertFalse(runtime.allowed_url("https://other.localhost"))
        with self.assertRaises(ValueError):
            runtime.checked_uuid("' OR true --")


class RuntimeDiagnosticsTest(unittest.TestCase):
    def test_command_failure_keeps_exit_code_without_dumping_external_output(self):
        result = subprocess.CompletedProcess(["docker", "compose"], 17, "", "private external output")
        with patch.object(runtime.subprocess, "run", return_value=result), redirect_stderr(io.StringIO()) as output:
            with self.assertRaisesRegex(runtime.VerificationError, "exit 17"):
                runtime.run(["docker", "compose"])
        self.assertNotIn("private external output", output.getvalue())

    def test_compose_failures_label_phase_and_bound_redacted_output(self):
        password = "a" * 64
        pem_line = "Q" * 96
        pem = "-----BEGIN PRIVATE KEY-----\n" + pem_line + "\n-----END PRIVATE KEY-----"
        stderr = ("old noise\n" * 100 + "x" * 20_000 + "\n" + password + "\n" + pem + "\nservice | "
                  + pem.replace("\n", "\nservice | ") + "\n" + json.dumps(pem)[1:-1]
                  + "\n::error::untrusted marker\ninitializer failed\n")
        for phase in ("Compose stack startup", "Compose edge restart"):
            for timed_out in (False, True):
                result = subprocess.CompletedProcess(["docker", "compose"], 17, "", stderr)
                failure = subprocess.TimeoutExpired(["docker", "compose"], 3, stderr=stderr.encode())
                with self.subTest(phase=phase, timed_out=timed_out), \
                        patch.object(runtime.subprocess, "run", side_effect=failure if timed_out else None, return_value=result), \
                        redirect_stderr(io.StringIO()) as output:
                    with self.assertRaises(runtime.VerificationError) as caught:
                        runtime.run(["docker", "compose"], label=phase, private_values=(password, pem))
                diagnostic = output.getvalue()
                self.assertIn(phase, str(caught.exception))
                self.assertIn("timed out" if timed_out else "exit 17", str(caught.exception))
                self.assertIn("initializer failed", diagnostic)
                self.assertIn("[redacted]", diagnostic)
                for private in (password, pem, pem_line, "-----BEGIN PRIVATE KEY-----", "-----END PRIVATE KEY-----"):
                    self.assertNotIn(private, diagnostic)
                self.assertLessEqual(len(diagnostic.splitlines()), runtime.DIAGNOSTIC_TAIL_LINES)
                self.assertLessEqual(len(diagnostic), runtime.DIAGNOSTIC_MAX_CHARS
                                     + runtime.DIAGNOSTIC_TAIL_LINES * (len(phase) + 24))
                self.assertTrue(all(line.startswith("DIAGNOSTIC ") for line in diagnostic.splitlines()))

    def test_startup_diagnostics_only_inspects_owned_state_and_initializer_tails(self):
        password = "b" * 64
        commands = []

        def docker(command, **kwargs):
            commands.append(command)
            name = command[-1]
            if command[1] == "logs":
                return subprocess.CompletedProcess(command, 0, "initializer stdout\n", "initialization failed " + password)
            if command[3] == "{{json .Config.Labels}}":
                owner = "other" if name.startswith("foreign-") else "bank10"
                return subprocess.CompletedProcess(command, 0, json.dumps({"com.docker.compose.project": owner}), "")
            return subprocess.CompletedProcess(command, 0, '{"status":"exited","exitCode":1,"oomKilled":false,"restarts":0}', "")

        containers = {"kafka-init": "bank10-kafka-init-1", "database": "bank10-database-1", "config-seed": "foreign-config-seed-1"}
        with patch.object(runtime.subprocess, "run", side_effect=docker), redirect_stderr(io.StringIO()) as output:
            runtime.startup_diagnostics(containers, (password,))
        diagnostic = output.getvalue()
        self.assertIn('"exitCode":1', diagnostic)
        self.assertIn("initializer stdout", diagnostic)
        self.assertIn("initialization failed [redacted]", diagnostic)
        self.assertIn("foreign-config-seed-1: unavailable", diagnostic)
        self.assertNotIn(password, diagnostic)
        self.assertEqual(["bank10-kafka-init-1"], [command[-1] for command in commands if command[1] == "logs"])
        self.assertEqual(1, sum(command[-1] == "foreign-config-seed-1" for command in commands))

    def test_external_cli_honors_secret_environment_and_explicit_override(self):
        for options, expected in (([], "/isolated/ci-secrets"), (["--secrets-dir", "/explicit/secrets"], "/explicit/secrets")):
            with self.subTest(options=options), patch.dict(os.environ, {"BANK_SECRETS_DIR": "/isolated/ci-secrets"}), \
                    patch.object(sys, "argv", ["verify-runtime.py", "--external", *options]), \
                    patch.object(runtime, "verify", return_value={}) as verify, redirect_stdout(io.StringIO()):
                runtime.main()
            self.assertEqual(Path(expected), verify.call_args.args[1])


if __name__ == "__main__":
    unittest.main()
