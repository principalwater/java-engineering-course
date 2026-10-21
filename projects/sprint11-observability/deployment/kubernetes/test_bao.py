"""Проверка клиента настройки OpenBao через настоящие TLS и HTTP без кластера и реальных секретов."""

import argparse
import base64
import contextlib
import http.server
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import ssl
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

import yaml

import bao
from prepare import project_dir


SPEC = importlib.util.spec_from_file_location("internal_tls", project_dir() / "deployment/scripts/init-internal-tls.py")
TLS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TLS)
IDENTITY = {"cluster": "bank11-test-single", "context": "kind-bank11-test-single", "kubeconfig": "unused",
            "namespace": "bank11-test"}
BINDINGS = {"bank11-accounts": {"spring.datasource.password": "db_accounts_password"},
            "bank11-cash": {"spring.datasource.password": "db_cash_password"},
            "bank11-gateway": {},
            "bank11-database": {"db_admin_password": "db_admin_password"},
            "bank11-edge": {"tls_ca.pem": "tls_ca.pem"}}
SOURCES = {"db_accounts_password": "accounts-value-1\n", "db_cash_password": "cash-value-1\n",
           "db_admin_password": "admin-value-1\n", "tls_ca.pem": "-----BEGIN CERTIFICATE-----\nQUJD\n-----END CERTIFICATE-----\n"}
CERTIFICATES = None

KUBECTL_STUB = """#!{python}
import json, os, signal, socket, socketserver, sys, threading

with open(os.environ["STUB_ARGV"], "w") as stream:
    json.dump(sys.argv[1:], stream)


class Relay(socketserver.BaseRequestHandler):
    def handle(self):
        upstream = socket.create_connection(("127.0.0.1", int(os.environ["STUB_PORT"])))

        def pump(source, sink):
            try:
                while chunk := source.recv(65536):
                    sink.sendall(chunk)
                sink.shutdown(socket.SHUT_WR)
            except OSError:
                pass

        reverse = threading.Thread(target=pump, args=(upstream, self.request))
        reverse.start()
        pump(self.request, upstream)
        reverse.join()


server = socketserver.ThreadingTCPServer(("127.0.0.1", 0), Relay)
signal.signal(signal.SIGTERM, lambda *ignored: (open(os.environ["STUB_STOPPED"], "w").close(), os._exit(0)))
print(f"Forwarding from 127.0.0.1:{{server.server_address[1]}} -> 8200", flush=True)
server.serve_forever()
"""
ORIGINAL_CONNECT, ORIGINAL_POD_CHECK = bao.connect, bao.openbao_pod


def setUpModule():
    global CERTIFICATES
    CERTIFICATES = tempfile.TemporaryDirectory(prefix="bank11-bao-tls-")
    TLS.initialize(Path(CERTIFICATES.name) / "trusted", ("openbao", "accounts"))
    TLS.initialize(Path(CERTIFICATES.name) / "foreign", ("openbao",))


def tearDownModule():
    CERTIFICATES.cleanup()


class FakeOpenBao:
    """Проверяет отправленные клиентом политики и роли по документированным структурам API."""

    def __init__(self):
        self.initialized, self.sealed, self.unseal_key, self.root_token = False, True, None, None
        self.mounts, self.auth, self.policies, self.roles, self.secrets = {}, {}, {}, {}, {}
        self.audit = {"file/": {"type": "file", "options": {"file_path": bao.AUDIT_FILE, "log_raw": "false"}}}
        self.tokens, self.revoked, self.calls, self.flaws, self.forbidden = {}, set(), [], set(), set()
        self.before_unseal = lambda: None

    def readable(self, token, path):
        if token == self.root_token or "paths" in self.flaws:
            return True
        granted = [re.findall(r'path "([^"]+)" \{\s*capabilities = \["read"\]', self.policies.get(name, ""))
                   for name in self.tokens[token]]
        return any(path == item or (item.endswith("*") and path.startswith(item[:-1])) for items in granted for item in items)

    def login(self, body):
        role = self.roles.get(body["role"])
        _, namespace, account, audience = body["jwt"].split("|")
        if (role is None
                or ("name" not in self.flaws and account not in role["bound_service_account_names"])
                or ("namespace" not in self.flaws and namespace not in role["bound_service_account_namespaces"])
                or ("audience" not in self.flaws and audience != role["audience"])):
            return 403, {"errors": ["permission denied"]}
        token = f"service-{len(self.tokens)}"
        self.tokens[token] = ["default", *role["token_policies"]]
        return 200, {"auth": {"client_token": token, "renewable": True, "lease_duration": role["token_ttl"],
                              "policies": self.tokens[token]}}

    def keyvalue(self, method, path, body, token):
        if not self.readable(token, f"kv/data/{path}") or (method != "GET" and token != self.root_token):
            return 403, {"errors": ["permission denied"]}
        entry = self.secrets.get(path)
        if method == "GET":
            return (404, None) if entry is None else (200, {"data": {"data": entry["data"], "metadata": {"version": entry["version"]}}})
        if body["options"]["cas"] != (entry["version"] if entry else 0) or (method == "PATCH" and entry is None):
            return 400, {"errors": ["check-and-set parameter did not match the current version"]}
        data = {**(entry["data"] if entry else {}), **body["data"]}
        self.secrets[path] = {"data": data, "version": (entry["version"] if entry else 0) + 1}
        return 200, {"data": {"version": self.secrets[path]["version"]}}

    def handle(self, method, path, token, body):
        self.calls.append((method, path))
        if path == "sys/seal-status":
            return 200, {"initialized": self.initialized, "sealed": self.sealed}
        if path == "sys/init":
            if self.initialized:
                return 400, {"errors": ["already initialized"]}
            self.initialized, self.unseal_key, self.root_token = True, secrets.token_hex(32), "root-" + secrets.token_hex(8)
            return 200, {"keys": [self.unseal_key], "keys_base64": [], "root_token": self.root_token}
        if path == "sys/unseal":
            self.before_unseal()
            self.sealed = body["key"] != self.unseal_key
            return 200, {"sealed": self.sealed}
        if self.sealed or not self.initialized:
            return 503, {"errors": ["Vault is sealed"]}
        if path == "sys/health":
            return 200, {"initialized": True, "sealed": False}
        if path == f"auth/{bao.AUTH_MOUNT}/login":
            return self.login(body)
        if token not in self.tokens and token != self.root_token or token in self.revoked:
            return 403, {"errors": ["permission denied"]}
        if path == "auth/token/lookup-self":
            return 200, {"data": {"policies": self.tokens.get(token, [])}}
        if path == "auth/token/renew-self":
            return 200, {"auth": {"renewable": True, "lease_duration": bao.TOKEN_TTL}}
        if path == "auth/token/revoke-self":
            if "revocation" not in self.flaws:
                self.revoked.add(token)
            return 204, None
        if path == "sys/capabilities-self":
            return 200, {item: ["read"] if self.readable(token, item) else ["deny"] for item in body["paths"]}
        if path.startswith("sys/internal/ui/mounts/kv/"):
            own = path.removeprefix("sys/internal/ui/mounts/kv/")
            if self.readable(token, "kv/data/" + own):
                return 200, {"data": {"type": "kv", "options": {"version": "2"}}}
            return 403, {"errors": ["permission denied"]}
        if path.startswith("kv/data/"):
            return self.keyvalue(method, path.removeprefix("kv/data/"), body, token)
        if token != self.root_token:
            return 403, {"errors": ["permission denied"]}
        if path == "sys/seal":
            self.sealed = "sealed" not in self.flaws
            return 204, None
        if path in self.forbidden:
            return 403, {"errors": ["permission denied"]}
        if path == "sys/storage/raft/snapshot":
            return 200, b"snapshot-bytes"
        collections = {"sys/mounts": self.mounts, "sys/auth": self.auth, "sys/audit": self.audit}
        if method == "GET" and path in collections:
            return 200, {"data": collections[path]}
        for prefix, store in (("sys/mounts/", self.mounts), ("sys/auth/", self.auth)):
            if method == "POST" and path.startswith(prefix):
                store[path.removeprefix(prefix) + "/"] = {"type": body["type"], "options": body.get("options", {})}
                return 204, None
        if path == f"auth/{bao.AUTH_MOUNT}/role" and method == "LIST":
            return 200, {"data": {"keys": list(self.roles)}}
        for prefix, store, field in ((f"auth/{bao.AUTH_MOUNT}/role/", self.roles, None), ("sys/policies/acl/", self.policies, "policy")):
            if path.startswith(prefix):
                name = path.removeprefix(prefix)
                if method == "POST":
                    store[name] = body[field] if field else body
                    return 204, None
                return 200, {"data": {field: store[name]} if field else store[name]}
        if path == f"auth/{bao.AUTH_MOUNT}/config":
            return 204, None
        return 404, None


class FakeServer(http.server.ThreadingHTTPServer):
    def handle_error(self, request, client_address):
        pass


class Handler(http.server.BaseHTTPRequestHandler):
    def respond(self):
        length = int(self.headers.get("Content-Length") or 0)
        status, payload = self.server.fake.handle(self.command, self.path.removeprefix("/v1/"),
                                                  self.headers.get("X-Vault-Token"),
                                                  json.loads(self.rfile.read(length)) if length else None)
        data = payload if isinstance(payload, bytes) else json.dumps(payload).encode() if payload is not None else b""
        self.send_response(status)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    do_GET = do_POST = do_PATCH = do_LIST = respond

    def log_message(self, *arguments):
        pass


@contextlib.contextmanager
def serving(fake, directory: Path, service: str = "openbao"):
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(directory / f"internal_{service}_certificate.pem", directory / f"internal_{service}_key.pem")
    server = FakeServer(("127.0.0.1", 0), Handler)
    server.fake = fake
    server.socket = context.wrap_socket(server.socket, server_side=True)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield server.server_address[1]
    finally:
        server.shutdown()
        server.server_close()


class BoundaryTest(unittest.TestCase):
    def test_certificate_must_be_trusted_and_issued_for_the_service_name(self):
        trusted, foreign = (Path(CERTIFICATES.name) / name for name in ("trusted", "foreign"))
        cases = (("trusted openbao identity", trusted, "openbao", None),
                 ("another service identity", trusted, "accounts", "TLS verification failed"),
                 ("untrusted authority", foreign, "openbao", "TLS verification failed"))
        for label, directory, service, failure in cases:
            with self.subTest(label), serving(FakeOpenBao(), directory, service) as port:
                client = bao.Bao(port, trusted / "internal_ca.pem")
                if failure is None:
                    self.assertFalse(client.request("GET", "sys/seal-status")["initialized"])
                else:
                    with self.assertRaisesRegex(bao.BaoError, failure):
                        client.request("GET", "sys/seal-status")

    def test_failures_are_classified_without_response_bodies_or_tokens(self):
        class Leaky(FakeOpenBao):
            def handle(self, method, path, token, body):
                return 403, {"errors": [f"echo {body['data']['field']}"]}

        trusted = Path(CERTIFICATES.name) / "trusted"
        with serving(Leaky(), trusted) as port:
            with self.assertRaises(bao.BaoError) as caught:
                bao.Bao(port, trusted / "internal_ca.pem").request(
                    "POST", "kv/data/x", token="token-value", body={"data": {"field": "field-value"}})
        self.assertEqual(caught.exception.status, 403)
        self.assertNotRegex(str(caught.exception), "token-value|field-value")


class Scenario(unittest.TestCase):
    """Каталоги состояния и внешних секретов с TLS-заглушкой вместо kubectl и port-forward."""

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="bank11-bao-")
        self.addCleanup(self.directory.cleanup)
        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)
        self.state, self.secrets = (Path(self.directory.name) / name for name in ("state", "secrets"))
        self.state.mkdir()
        self.secrets.mkdir(mode=0o700)
        trusted = Path(CERTIFICATES.name) / "trusted"
        for name in ("internal_ca.pem", "internal_openbao_certificate.pem", "internal_openbao_key.pem"):
            shutil.copy(trusted / name, self.secrets / name)
        for name, text in SOURCES.items():
            self.source(name, text)
        (self.state / "secret-bindings.json").write_text(json.dumps(BINDINGS))
        self.fake, self.cluster, self.cluster_calls = FakeOpenBao(), {}, []
        self.port = self.stack.enter_context(serving(self.fake, trusted))
        self.args = argparse.Namespace(state_dir=self.state, secrets_dir=self.secrets, exercise_seal=False)

        @contextlib.contextmanager
        def forward(identity, secret_dir):
            yield bao.Bao(self.port, secret_dir / "internal_ca.pem")

        for target, replacement in (("owned_identity", lambda state_dir: IDENTITY), ("connect", forward),
                                    ("require_private_source", Path), ("kubectl", self.kubectl)):
            self.stack.enter_context(patch.object(bao, target, replacement))

    def source(self, name, text, mode=0o600):
        path = self.secrets / name
        path.write_text(text)
        path.chmod(mode)

    def kubectl(self, identity, *argv, data=None, **kwargs):
        if argv[:2] == ("create", "token"):
            return f"jwt|{identity['namespace']}|{argv[2]}|{argv[argv.index('--audience') + 1] if '--audience' in argv else 'api'}\n"
        if argv[0] == "get":
            return json.dumps(self.cluster[(argv[1], argv[2])]) if (argv[1], argv[2]) in self.cluster else ""
        document = json.loads(data) if data else None
        self.cluster_calls.append((argv[0], identity["namespace"], document or argv))
        if argv[0] == "create" and document["kind"] != "List":
            self.cluster[(document["kind"].lower(), document["metadata"]["name"])] = document
        return ""

    def run_operation(self, operation, **overrides):
        for key, value in overrides.items():
            setattr(self.args, key, value)
        with contextlib.redirect_stdout(io.StringIO()) as output:
            bao.OPERATIONS[operation](self.args)
        return output.getvalue()

    def recovery(self):
        return bao.recovery_path(self.secrets, IDENTITY["cluster"])

    def writes(self):
        return [call for call in self.fake.calls if call[0] in ("POST", "PATCH") and call[1].startswith("kv/data/")]


class LifecycleTest(Scenario):
    def test_bootstrap_saves_recovery_once_and_resume_only_unseals(self):
        saved_before_unseal = []
        self.fake.before_unseal = lambda: saved_before_unseal.append(self.recovery().is_file())
        self.run_operation("bootstrap")
        self.assertEqual(saved_before_unseal, [True])
        saved = json.loads(self.recovery().read_text())
        self.assertEqual((saved["unseal_key"], saved["root_token"]), (self.fake.unseal_key, self.fake.root_token))
        self.assertEqual(self.recovery().stat().st_mode & 0o777, 0o600)
        self.assertFalse(self.fake.sealed)
        before = self.recovery().read_bytes()
        self.run_operation("bootstrap")
        self.fake.sealed = True
        self.run_operation("unseal")
        self.assertEqual(self.recovery().read_bytes(), before)
        self.assertEqual(self.fake.calls.count(("POST", "sys/init")), 1)
        self.assertFalse(self.fake.sealed)
        self.assertEqual(list(self.recovery().parent.glob(".*")), [])

    def test_never_adopts_or_resets_existing_state(self):
        self.fake.initialized, self.fake.unseal_key = True, "ab" * 32
        with self.assertRaisesRegex(ValueError, "recovery file is absent"):
            self.run_operation("bootstrap")
        self.fake.initialized = False
        self.recovery().parent.mkdir(parents=True)
        self.recovery().write_text("retained recovery")
        with self.assertRaisesRegex(ValueError, "Retained OpenBao recovery material"):
            self.run_operation("bootstrap")
        with self.assertRaisesRegex(ValueError, "not initialized"):
            self.run_operation("unseal")
        self.assertEqual(self.recovery().read_text(), "retained recovery")
        self.assertNotIn(("POST", "sys/init"), self.fake.calls)

    def test_snapshot_is_private_and_a_failed_download_leaves_nothing(self):
        self.run_operation("bootstrap")
        self.run_operation("snapshot")
        saved = list((self.recovery().parent / "snapshots").iterdir())
        self.assertEqual([(path.read_bytes(), path.stat().st_mode & 0o777) for path in saved], [(b"snapshot-bytes", 0o600)])
        saved[0].unlink()
        self.fake.forbidden.add("sys/storage/raft/snapshot")
        with self.assertRaises(bao.BaoError):
            self.run_operation("snapshot")
        self.assertEqual(list((self.recovery().parent / "snapshots").iterdir()), [])


class ConfigurationTest(Scenario):
    def setUp(self):
        super().setUp()
        self.run_operation("bootstrap")

    def test_configure_import_and_verify_agree_and_are_repeatable(self):
        self.run_operation("configure")
        self.assertEqual(set(self.fake.roles), {"bank11-test-accounts", "bank11-test-cash", "bank11-test-eso"})
        self.assertEqual(self.fake.roles["bank11-test-eso"]["bound_service_account_names"], ["eso"])
        printed = self.run_operation("import")
        self.assertEqual({path: entry["data"] for path, entry in self.fake.secrets.items()},
                         {"bank11-test/accounts": {"spring.datasource.password": "accounts-value-1"},
                          "bank11-test/cash": {"spring.datasource.password": "cash-value-1"},
                          "bank11-test/database": {"db_admin_password": "admin-value-1"},
                          "bank11-test/edge": {"tls_ca.pem": SOURCES["tls_ca.pem"]}})
        self.assertNotRegex(printed, "-value-1")
        self.run_operation("configure")
        written = len(self.writes())
        self.run_operation("import")
        self.assertEqual(len(self.writes()), written)
        self.run_operation("verify", exercise_seal=True)
        self.assertFalse(self.fake.sealed)
        created = next(call[2] for call in self.cluster_calls if call[0] == "create")
        labels = {tuple(item["metadata"]["labels"].items()) for item in created["items"]}
        deleted = next(call[2] for call in self.cluster_calls if call[0] == "delete")
        self.assertEqual(len(labels), 1)
        self.assertIn(tuple(deleted[deleted.index("-l") + 1].split("=")), next(iter(labels)))

    def test_import_adds_new_keys_but_never_rotates_existing_values(self):
        self.run_operation("configure")
        self.run_operation("import")
        self.fake.secrets["bank11-test/accounts"]["data"]["pushed.by.operator"] = "kept"
        self.source("db_cash_password", "cash-value-2\n")
        with self.assertRaisesRegex(ValueError, "rotation is required.*bank11-test/cash"):
            self.run_operation("import")
        self.assertEqual(self.fake.secrets["bank11-test/cash"]["data"], {"spring.datasource.password": "cash-value-1"})
        self.source("db_cash_password", "cash-value-1\n")
        self.source("extra_key", "extra-value\n")
        (self.state / "secret-bindings.json").write_text(json.dumps(
            {**BINDINGS, "bank11-accounts": {**BINDINGS["bank11-accounts"], "extra.property": "extra_key"}}))
        self.run_operation("import")
        self.assertEqual(self.fake.secrets["bank11-test/accounts"]["data"],
                         {"spring.datasource.password": "accounts-value-1", "pushed.by.operator": "kept",
                          "extra.property": "extra-value"})
        self.assertEqual(self.fake.secrets["bank11-test/accounts"]["version"], 2)

    def test_import_validates_every_source_before_the_first_write(self):
        self.run_operation("configure")
        self.source("db_cash_password", "cash-value-1\n", mode=0o644)
        with self.assertRaisesRegex(ValueError, "not private: db_cash_password"):
            self.run_operation("import")
        self.assertEqual(self.writes(), [])

    def test_configure_refuses_unsafe_audit_and_foreign_mounts_before_writing(self):
        self.fake.audit["file/"]["options"]["log_raw"] = "true"
        patience = bao.eventually
        with patch.object(bao, "eventually", lambda check, message, timeout=60: patience(check, message, 0)):
            with self.assertRaisesRegex(RuntimeError, "HMAC file audit device"):
                self.run_operation("configure")
        self.assertEqual((self.fake.mounts, self.fake.auth), ({}, {}))
        self.fake.audit["file/"]["options"]["log_raw"] = "false"
        self.fake.mounts["kv/"] = {"type": "kv", "options": {"version": "1"}}
        with self.assertRaisesRegex(ValueError, "adoption is not supported"):
            self.run_operation("configure")
        self.assertEqual(self.fake.roles, {})

    def test_verify_fails_for_each_broken_boundary_and_cleans_its_accounts(self):
        self.run_operation("configure")
        self.run_operation("import")
        reasons = {"audience": "default API audience", "name": "another service account",
                   "namespace": "another namespace", "paths": "read bank11-test/|capabilities on",
                   "revocation": "survived revocation", "sealed": "sealed server served", "unmanaged": "unmanaged or missing roles"}
        for flaw, reason in reasons.items():
            with self.subTest(flaw):
                self.fake.flaws.add(flaw)
                if flaw == "unmanaged":
                    self.fake.roles["rogue"] = {}
                self.cluster_calls.clear()
                with self.assertRaisesRegex(RuntimeError, f"verification failed: .*({reason})"):
                    self.run_operation("verify", exercise_seal=flaw == "sealed")
                created = [call for call in self.cluster_calls if call[0] == "create"]
                deleted = [call for call in self.cluster_calls if call[0] == "delete"]
                self.assertEqual(len(deleted), len(created))
                self.fake.flaws.clear()
                self.fake.roles.pop("rogue", None)
                self.fake.sealed = False
        self.run_operation("verify")


class TlsSecretTest(Scenario):
    def test_tls_secret_is_created_once_and_rotation_is_explicit(self):
        self.run_operation("tls")
        namespace, secret = self.cluster[("namespace", bao.OPENBAO_NAMESPACE)], self.cluster[("secret", bao.TLS_SECRET)]
        self.assertEqual(namespace["metadata"]["labels"][bao.OWNER_LABEL], IDENTITY["cluster"])
        self.assertEqual(secret["type"], "kubernetes.io/tls")
        self.assertEqual({key: base64.b64decode(value) for key, value in secret["data"].items()},
                         {key: (self.secrets / name).read_bytes() for key, name in bao.TLS_FILES.items()})
        self.cluster_calls.clear()
        self.run_operation("tls")
        self.assertEqual(self.cluster_calls, [])
        shutil.copy(Path(CERTIFICATES.name) / "foreign" / "internal_openbao_certificate.pem", self.secrets)
        shutil.copy(Path(CERTIFICATES.name) / "foreign" / "internal_openbao_key.pem", self.secrets)
        with self.assertRaisesRegex(ValueError, "rotation is required"):
            self.run_operation("tls")
        self.cluster[("namespace", bao.OPENBAO_NAMESPACE)]["metadata"]["labels"][bao.OWNER_LABEL] = "another"
        with self.assertRaisesRegex(ValueError, "another deployment"):
            self.run_operation("tls")


class ForwardTest(Scenario):
    def install_pod(self, image=None, owner=IDENTITY["cluster"]):
        self.cluster[("namespace", bao.OPENBAO_NAMESPACE)] = {"metadata": {"labels": {bao.OWNER_LABEL: owner}}}
        self.cluster[("pod", bao.POD)] = {
            "metadata": {"labels": {"app.kubernetes.io/name": "openbao", "app.kubernetes.io/instance": "openbao",
                                    "component": "server"}},
            "spec": {"containers": [{"name": "openbao", "image": image or bao.expected_image()}]},
            "status": {"containerStatuses": [{"name": "openbao", "state": {"running": {}}}]}}

    def test_only_the_pinned_pod_of_the_owned_namespace_is_trusted(self):
        self.install_pod()
        ORIGINAL_POD_CHECK(IDENTITY)
        for label, arguments, failure in (("image", {"image": "quay.io/openbao/openbao:latest"}, "pinned OpenBao"),
                                          ("namespace", {"owner": "another"}, "another deployment")):
            with self.subTest(label):
                self.install_pod(**arguments)
                with self.assertRaisesRegex(ValueError, failure):
                    ORIGINAL_POD_CHECK(IDENTITY)

    def test_port_forward_tunnels_verified_tls_to_the_pod_and_is_stopped(self):
        self.install_pod()
        stub = Path(self.directory.name) / "bin"
        stub.mkdir()
        (stub / "kubectl").write_text(KUBECTL_STUB.format(python=sys.executable))
        (stub / "kubectl").chmod(0o700)
        files = {name: Path(self.directory.name) / name for name in ("argv", "stopped")}
        environment = {"PATH": f"{stub}:{os.environ['PATH']}", "STUB_PORT": str(self.port),
                       "STUB_ARGV": str(files["argv"]), "STUB_STOPPED": str(files["stopped"])}
        with patch.dict(os.environ, environment), ORIGINAL_CONNECT(IDENTITY, self.secrets) as client:
            self.assertFalse(client.request("GET", "sys/seal-status")["initialized"])
            self.assertFalse(files["stopped"].exists())
        self.assertEqual(json.loads(files["argv"].read_text()),
                         ["--kubeconfig", "unused", "--context", IDENTITY["context"], "--namespace", bao.OPENBAO_NAMESPACE,
                          "port-forward", "--address", "127.0.0.1", f"pod/{bao.POD}", f":{bao.API_PORT}"])
        deadline = time.monotonic() + 10
        while not files["stopped"].exists() and time.monotonic() < deadline:
            time.sleep(0.05)
        self.assertTrue(files["stopped"].exists())


class ValuesContractTest(unittest.TestCase):
    def test_helm_values_keep_verified_tls_persistent_raft_digest_pins_and_hmac_audit(self):
        values = yaml.safe_load((project_dir() / "deployment/openbao/values.yaml").read_text())
        server, raft = values["server"], values["server"]["ha"]
        self.assertFalse(values["global"]["tlsDisable"])
        self.assertEqual((raft["enabled"], raft["replicas"], raft["raft"]["enabled"]), (True, 1, True))
        self.assertTrue(server["dataStorage"]["enabled"] and server["auditStorage"]["enabled"])
        config = raft["raft"]["config"]
        self.assertRegex(config, r'tls_cert_file\s*=')
        self.assertNotRegex(config, r"tls_disable|log_raw\s*=\s*\"true\"")
        self.assertRegex(bao.expected_image(), r"@sha256:[0-9a-f]{64}$")
        self.assertEqual({container["image"] for container in server["extraContainers"]}, {bao.expected_image()})


if __name__ == "__main__":
    unittest.main()
