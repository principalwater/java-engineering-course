"""Контракты разбора собственной формы и границы безопасного runtime-клиента."""

import importlib.util
import json
from pathlib import Path
import unittest
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
        self.assertEqual("https://bank.valgrind-home.localhost:8449/login?logout", front["attributes"]["post.logout.redirect.uris"])

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
        base = "https://bank.valgrind-home.localhost:8449"
        guard = runtime.SafeRedirect(base)
        for callback in ("http://bank.valgrind-home.localhost:8449/login/oauth2/code/bank", "https://evil.example/callback"):
            target = "https://auth-bank.valgrind-home.localhost:8449/realms/bank/protocol/openid-connect/auth?" + urlencode({"redirect_uri": callback})
            with self.assertRaises(runtime.VerificationError):
                guard.redirect_request(Request(base), None, 302, "Found", {}, target)
        self.assertEqual(0, guard.authorization_redirects)

    def test_target_and_uuid_boundaries_reject_credentials_and_sql_fragments(self):
        self.assertFalse(runtime.allowed_url("https://alice:secret@bank.valgrind-home.localhost:8449"))
        self.assertFalse(runtime.allowed_url("http://bank.valgrind-home.localhost:8449"))
        self.assertFalse(runtime.allowed_url("https://other.localhost"))
        with self.assertRaises(ValueError):
            runtime.checked_uuid("' OR true --")


if __name__ == "__main__":
    unittest.main()
