"""Контракты локального стенда Jenkins, которым не нужен запущенный контейнер: снимок кода, внешние секреты, изоляция."""

import argparse
import contextlib
import http.server
import io
import json
import os
from pathlib import Path
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import unittest
import urllib.request
from unittest import mock

import ci


class DiagnosticsTest(unittest.TestCase):
    def test_static_failure_messages_come_from_source_without_dynamic_values(self):
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            path = project / "deployment/scripts/verify-runtime.py"
            path.parent.mkdir(parents=True)
            path.write_text('require(ok, "fixed failure")\nwait_for("notification delivery", probe)\n'
                            'raise TransportUnavailable("fixed transport")\nrequire(ok, f"{private_value}")\n', encoding="utf-8")
            self.assertEqual(ci.verification_failures(project),
                             {"FAIL fixed failure", "FAIL Timed out: notification delivery", "FAIL fixed transport"})

    def test_reports_only_fixed_failure_categories_and_typed_workload_states(self):
        console = ("FAIL Timed out: recovered cabinet notification\n"
                   "BANK_CI_POD accounts ready=0 restarts=2 reason=Running last=OOMKilled\n"
                   "BANK_CI_NODE DiskPressure True\n"
                   "BANK_CI_TOFU deadline apply\n"
                   "BANK_CI_HOST mem_available_mib=123 swap_used_mib=3 oom_kills=7 disk_used_pct=25\n"
                   "FAIL Command timed out: Kubernetes rollout\n"
                   "BANK_CI_POD private-value ready=0 restarts=0 reason=Error\n"
                   "BANK_CI_POD cash ready=0 restarts=0 reason=private-value\n"
                   "BANK_CI_NODE Ready private-value\nprivate-value\n")
        summary = ci.console_diagnostics(console, set(), {"FAIL Timed out: recovered cabinet notification"})
        self.assertIn("FAIL Timed out: recovered cabinet notification", summary)
        self.assertIn("FAIL Command timed out: Kubernetes rollout", summary)
        self.assertIn("BANK_CI_POD accounts ready=0 restarts=2 reason=Running last=OOMKilled", summary)
        self.assertIn("BANK_CI_NODE DiskPressure True", summary)
        self.assertIn("BANK_CI_TOFU deadline apply", summary)
        self.assertIn("BANK_CI_HOST mem_available_mib=123", summary)
        self.assertNotIn("private-value", summary)

    def test_successful_early_stage_warnings_are_not_failure_indicators(self):
        console = ("[Pipeline] { (Build)\nTimeoutException\nERROR: script returned exit code 42\n"
                   "[Pipeline] { (Verify)\nERROR: script returned exit code 1\n")
        summary = ci.console_diagnostics(console, {"Build", "Verify"})
        self.assertIn("shell exit codes=[1]", summary)
        self.assertIn("console indicators=[]", summary)


def commit(repository: Path, name: str, content: str, *, amend: bool = False) -> None:
    (repository / name).write_text(content)
    ci.git(["add", name], cwd=repository)
    options = ["-c", "user.name=Test", "-c", "user.email=test@example.invalid", "-c", "commit.gpgsign=false"]
    ci.git([*options, "commit", "--quiet", *(["--amend", "--no-edit"] if amend else []), "-m", f"add {name}"], cwd=repository)


class SnapshotTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.repository = Path(self.directory.name) / "repository"
        self.bare = Path(self.directory.name) / "snapshot.git"
        self.repository.mkdir()
        ci.git(["init", "--quiet"], cwd=self.repository)
        pipeline = (ci.HERE.parents[1] / "Jenkinsfile").relative_to(ci.REPOSITORY)
        (self.repository / pipeline).parent.mkdir(parents=True)
        (self.repository / pipeline).write_text("""pipeline {
    stages {
        stage('Checkout') { steps { echo 'checkout' } }
        stage('Build and test') { steps { sh 'test-command' } }
        stage('Deploy') { steps { sh 'deploy-command' } }
    }
}
""")
        ci.git(["add", str(pipeline)], cwd=self.repository)
        for index in range(3):
            commit(self.repository, f"file{index}.txt", f"content {index}\n")

    def published(self) -> str:
        return ci.git(["--git-dir", str(self.bare), "rev-parse", f"refs/heads/{ci.SNAPSHOT_BRANCH}"]).strip()

    def test_publishes_the_whole_history_and_follows_rewritten_commits(self):
        """Jenkins обязан собирать точный непушнутый HEAD с микрокоммитами, в том числе после amend последнего коммита."""
        head = ci.snapshot(self.repository, self.bare)
        self.assertEqual(head, self.published())
        self.assertEqual("3", ci.git(["--git-dir", str(self.bare), "rev-list", "--count", f"refs/heads/{ci.SNAPSHOT_BRANCH}"]).strip())
        clone = Path(self.directory.name) / "clone"
        ci.git(["clone", "--quiet", "--branch", ci.SNAPSHOT_BRANCH, str(self.bare), str(clone)])
        self.assertEqual("content 2\n", (clone / "file2.txt").read_text())

        commit(self.repository, "file2.txt", "rewritten\n", amend=True)
        amended = ci.snapshot(self.repository, self.bare)
        self.assertNotEqual(head, amended)
        self.assertEqual(amended, self.published())

    def test_refuses_uncommitted_changes_and_keeps_the_previous_snapshot(self):
        """Сборка закоммиченной истории не должна молча пропускать работу, которая есть только в рабочем дереве."""
        head = ci.snapshot(self.repository, self.bare)
        (self.repository / "file0.txt").write_text("edited\n")
        (self.repository / "untracked.txt").write_text("new\n")
        with self.assertRaisesRegex(ValueError, "uncommitted or untracked"):
            ci.snapshot(self.repository, self.bare)
        ci.git(["checkout", "--quiet", "--", "file0.txt"], cwd=self.repository)
        with self.assertRaisesRegex(ValueError, "uncommitted or untracked"):
            ci.snapshot(self.repository, self.bare)
        self.assertEqual(head, self.published())

    def test_failed_build_keeps_its_untrusted_console_out_of_the_ci_log(self):
        """Ошибка pipeline может содержать секрет; внешний runner получает только статус и путь приватного журнала."""
        layout = ci.Layout(Path(self.directory.name) / "state", Path(self.directory.name) / "secrets")
        layout.logs.mkdir(parents=True)
        controller = mock.Mock()
        controller.started_build.return_value = 1
        sensitive = "fixture-credential-must-stay-private"
        controller.follow.side_effect = lambda number, log, deadline, stages: ci.write_private(log, b"")
        controller.completed_console.side_effect = lambda number, log: ci.write_private(log, sensitive.encode())
        controller.json.return_value = {"building": False, "result": "FAILURE"}
        captured = io.StringIO()
        environment = {"BANK_JENKINS_HOME": str(layout.root), ci.SECRETS_ENVIRONMENT: str(layout.secret_source),
                       ci.PLAINTEXT_ENVIRONMENT: "true"}
        with mock.patch.dict(os.environ, environment), mock.patch.object(ci.sys, "platform", "linux"), \
                mock.patch.object(ci, "REPOSITORY", self.repository), \
                mock.patch.object(ci, "Controller", return_value=controller), \
                contextlib.redirect_stdout(captured), contextlib.redirect_stderr(captured):
            with self.assertRaisesRegex(RuntimeError, "finished with FAILURE"):
                ci.command_build(argparse.Namespace(timeout=1))
        self.assertNotIn(sensitive, captured.getvalue())
        log = layout.logs / "build-1.log"
        self.assertEqual(sensitive, log.read_text())
        self.assertEqual(0o600, log.stat().st_mode & 0o777)

    def test_controller_plugins_and_completed_console_contracts(self):
        """Полная консоль подтверждает узел и выполненный этап; секреты и пропущенные этапы не попадают в сводку."""
        layout = ci.Layout(Path(self.directory.name) / "state", Path(self.directory.name) / "secrets")
        layout.logs.mkdir(parents=True)
        head = ci.git(["rev-parse", "HEAD"], cwd=self.repository).strip()
        sensitive = "fixture-credential-must-stay-private"
        complete = f"[Pipeline] Start of Pipeline\nRunning on {ci.AGENT_NAME} in /workspace\n{sensitive}\n"
        pins = [line.split(":", 1) for line in (ci.HERE / "controller/plugins.txt").read_text().splitlines()
                if line.strip() and not line.lstrip().startswith("#")]
        scenario = {}

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def send(self, status, body=b"", headers=None):
                self.send_response(status)
                for name, value in (headers or {}).items():
                    self.send_header(name, value)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_POST(self):
                self.send(201, headers={"Location": "/queue/item/1/"})

            def do_GET(self):
                if self.path == "/login":
                    self.send(200)
                elif self.path == "/crumbIssuer/api/json":
                    self.send(200, json.dumps({"crumbRequestField": "Jenkins-Crumb", "crumb": "fixture"}).encode())
                elif self.path.startswith(f"/computer/{ci.AGENT_NAME}/"):
                    self.send(200, b'{"offline":false}')
                elif self.path.startswith("/pluginManager/api/json?"):
                    if self.headers.get("Authorization") != "Basic fixture":
                        self.send(403, sensitive.encode())
                        return
                    plugins = [{"shortName": name, "version": version, "active": True, "enabled": True}
                               for name, version in pins]
                    target = next(plugin for plugin in plugins if plugin["shortName"] == "plain-credentials")
                    problem = scenario.get("plugin_problem")
                    if problem == "missing":
                        plugins.remove(target)
                    elif problem:
                        target[problem] = sensitive if problem == "version" else False
                    self.send(200, json.dumps({"plugins": plugins}).encode())
                elif self.path == "/queue/item/1/api/json":
                    self.send(200, b'{"executable":{"number":1}}')
                elif "/logText/progressiveText?" in self.path:
                    body = scenario.get("progressive", "").encode()
                    self.send(200, body, {"X-Text-Size": str(len(body))})
                elif self.path.endswith("/consoleText"):
                    self.send(scenario.get("status", 200), scenario["console"].encode())
                elif self.path.startswith("/computer/api/json?"):
                    self.send(200, json.dumps({"computer": [
                        {"displayName": "Built-In Node", "numExecutors": scenario.get("executors", 0)},
                        {"displayName": ci.AGENT_NAME, "numExecutors": 1}]}).encode())
                else:
                    self.send(200, json.dumps({"building": False, "result": scenario.get("result", "SUCCESS"), "actions": [
                        {"lastBuiltRevision": {"SHA1": scenario.get("revision", head)}}]}).encode())

        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(thread.join, 5)
        self.addCleanup(server.shutdown)
        controller = ci.Controller.__new__(ci.Controller)
        controller._base = f"http://127.0.0.1:{server.server_port}"
        controller._authorization = "Basic fixture"
        controller._opener = urllib.request.build_opener(ci.NoRedirect)
        controller.verify_plugins()
        for problem in ("missing", "active", "enabled", "version"):
            scenario["plugin_problem"] = problem
            with self.subTest(plugin_problem=problem), self.assertRaisesRegex(RuntimeError, "plain-credentials") as failure:
                controller.verify_plugins()
            self.assertNotIn(sensitive, str(failure.exception))
        failed = (complete + "[Pipeline] { (Checkout)\n[Pipeline] { (Build and test)\n"
                  f"No space left on device: {sensitive}\ntofu phase failed (exit 1)\n"
                  "[Pipeline] { (Deploy)\nStage \"Deploy\" skipped due to earlier failure(s)\n"
                  f"[Pipeline] {{ ({sensitive})\nERROR: script returned exit code 137\n"
                  "ERROR: script returned exit code 1\n")
        cases = [
            ({"console": complete}, None),
            ({"console": ""}, "completed bytes=0; pipeline markers=0"),
            ({"console": f"[Pipeline] Start of Pipeline\n{sensitive}\n"}, "pipeline markers=1"),
            ({"console": "[Pipeline] Start of Pipeline\n[Pipeline] End of Pipeline\n"},
             r"last executed stage=none; shell exit codes=\[\]; console indicators=\[\]"),
            ({"console": failed, "result": "FAILURE", "progressive": f"[Pipeline] {{ ({sensitive})\n"},
             r"last executed stage=Build and test; shell exit codes=\[1, 137\]; console indicators=\[disk-space, deployment\]"),
            ({"console": complete + '[Pipeline] { (Checkout)\n[Pipeline] { (Build and test)\n'
                         'Stage "Build and test" skipped due to earlier stage(s) marking the build as unstable\n'
                         '[Pipeline] { (Deploy)\nStage "Deploy" skipped due to when conditional\n', "result": "FAILURE"},
             r"last executed stage=Checkout; shell exit codes=\[\]; console indicators=\[\]"),
            ({"console": sensitive, "status": 403}, "completed console.*HTTP 403"),
            ({"console": sensitive, "limit": 8}, "response exceeded.*byte limit"),
            ({"console": complete.replace(ci.AGENT_NAME, sensitive)}, "unexpected nodes=1"),
            ({"console": complete, "result": sensitive}, "finished with UNKNOWN"),
            ({"console": complete, "revision": sensitive}, "used revisions"),
            ({"console": complete, "executors": 1}, "controller has executors"),
        ]
        environment = {"BANK_JENKINS_HOME": str(layout.root), ci.SECRETS_ENVIRONMENT: str(layout.secret_source),
                       ci.PLAINTEXT_ENVIRONMENT: "true"}
        for values, expected in cases:
            scenario.clear()
            scenario.update(values)
            captured = io.StringIO()
            with self.subTest(expected=expected), mock.patch.dict(os.environ, environment), \
                    mock.patch.object(ci.sys, "platform", "linux"), \
                    mock.patch.object(ci, "REPOSITORY", self.repository), \
                    mock.patch.object(ci, "Controller", return_value=controller), \
                    mock.patch.object(ci, "CONSOLE_MAX_BYTES", values.get("limit", 32 * 1024 * 1024), create=True), \
                    contextlib.redirect_stdout(captured), contextlib.redirect_stderr(captured):
                if expected:
                    with self.assertRaisesRegex(RuntimeError, expected) as failure:
                        ci.command_build(argparse.Namespace(timeout=5))
                    self.assertNotIn(sensitive, str(failure.exception))
                else:
                    ci.command_build(argparse.Namespace(timeout=5))
                    self.assertIn("succeeded only", captured.getvalue())
                    log = layout.logs / "build-1.log"
                    self.assertEqual(complete, log.read_text())
                    self.assertEqual(0o600, log.stat().st_mode & 0o777)
                self.assertNotIn(sensitive, captured.getvalue())


class SecretSourceTest(unittest.TestCase):
    """Источник секретов задаёт оператор; ни при каких условиях он не появляется неявно в открытом ~/.local/share."""

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.home = Path(self.directory.name) / "home"
        self.home.mkdir()
        self.supplied = Path(self.directory.name) / "supplied"
        self.supplied.mkdir(mode=0o700)

    def environment(self, **values: str):
        base = {"HOME": str(self.home), "BANK_JENKINS_HOME": str(Path(self.directory.name) / "open-state")}
        return mock.patch.dict(os.environ, {**base, **values}, clear=True)

    def test_unsafe_secret_locations_are_refused_and_no_default_is_invented(self):
        """Прежняя версия молча выбирала каталог в ~/.local/share и клала туда пароль и ключ TLS без шифрования."""
        link = Path(self.directory.name) / "link"
        link.symlink_to(self.supplied)
        cases = {"unset": {}, "inside the repository": {ci.SECRETS_ENVIRONMENT: str(ci.REPOSITORY / "jenkins-secrets")},
                 "symlink": {ci.SECRETS_ENVIRONMENT: str(link)}}
        for name, values in cases.items():
            with self.subTest(name), self.environment(**values), self.assertRaises(ValueError):
                ci.layout_from_environment()
        self.assertEqual([], list(self.home.rglob("*")))

    @mock.patch.object(ci.sys, "platform", "linux")
    def test_linux_file_secrets_need_the_isolated_ci_flag(self):
        layout = ci.Layout(self.home, self.supplied)
        with self.environment():
            with self.assertRaisesRegex(ValueError, "isolated disposable CI"):
                ci.require_secret_source(layout)
        with self.environment(**{ci.PLAINTEXT_ENVIRONMENT: "true"}):
            ci.require_secret_source(layout)

    @unittest.skipUnless(sys.platform == "darwin", "macOS policy")
    def test_macos_refuses_a_plain_directory_even_with_the_ci_flag(self):
        """Флаг изолированного CI не должен превращать обычный каталог на Mac в допустимое хранилище секретов."""
        layout = ci.Layout(self.home, self.supplied)
        with self.environment(**{ci.PLAINTEXT_ENVIRONMENT: "true"}):
            with self.assertRaisesRegex(ValueError, "encrypted disk"):
                ci.require_secret_source(layout)


class ExternalSecretsTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.layout = ci.Layout(Path(self.directory.name) / "home", Path(self.directory.name) / "secrets")
        # Криптографические fixtures никогда не читают настоящий ключ mkcert разработчика.
        platform = mock.patch.object(ci.sys, "platform", "linux")
        platform.start()
        self.addCleanup(platform.stop)
        try:
            ci.openssl_binary()
        except RuntimeError as error:
            self.skipTest(str(error))

    def handshake(self, server_name: str) -> None:
        """Сервер отдаёт цепочку контроллера, клиент проверяет её как агент и этот скрипт: строгий X.509, только свой CA."""
        server_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        server_context.load_cert_chain(self.layout.controller_secrets / "tls_certificate.pem",
                                       self.layout.controller_secrets / "tls_private_key.pem")
        client_context = ssl.create_default_context(cafile=str(self.layout.agent_secrets / "tls_ca.pem"))
        client_context.verify_flags |= ssl.VERIFY_X509_STRICT
        server_socket, client_socket = socket.socketpair()
        failures = []

        def serve():
            try:
                with server_context.wrap_socket(server_socket, server_side=True) as stream:
                    stream.recv(1)
            except (OSError, ssl.SSLError) as error:
                failures.append(error)

        thread = threading.Thread(target=serve)
        thread.start()
        try:
            with client_context.wrap_socket(client_socket, server_hostname=server_name) as stream:
                stream.sendall(b"x")
        finally:
            thread.join(timeout=10)
        self.assertEqual([], failures)

    def test_generated_secrets_are_private_stable_and_the_chain_is_trusted(self):
        """Клиенты на loopback и в приватной сети должны доверять одной сгенерированной цепочке."""
        captured = io.StringIO()
        with contextlib.redirect_stdout(captured):
            ci.initialize(self.layout)
        password = (self.layout.controller_secrets / "admin_password").read_bytes()
        # Режимы проверяются сразу после создания: повторный запуск применяет их заново и скрыл бы читаемую первую запись.
        for directory in (self.layout.root, self.layout.secret_source, self.layout.jenkins_secrets,
                          self.layout.controller_secrets, self.layout.agent_secrets):
            self.assertEqual(0o700, directory.stat().st_mode & 0o777)
        for path in [*self.layout.controller_secrets.iterdir(), *self.layout.agent_secrets.iterdir()]:
            self.assertEqual(0o600, path.stat().st_mode & 0o777, path.name)
        for name in ("localhost", "127.0.0.1", "::1", ci.CONTROLLER):
            self.handshake(name)

        ci.initialize(self.layout)
        self.assertEqual(password, (self.layout.controller_secrets / "admin_password").read_bytes())
        self.assertNotIn(password.strip().decode(), captured.getvalue())

        def with_private_key(directory: Path) -> list[Path]:
            return [path for path in directory.rglob("*") if path.is_file() and b"PRIVATE KEY" in path.read_bytes()]

        # Агент может узнать, кто подписывает контроллер, но не получает приватный ключ; ключ подписи CA уничтожается,
        # а открытое состояние стенда не содержит ключевого материала вообще (в том числе временного каталога выпуска).
        self.assertEqual([self.layout.controller_secrets / "tls_private_key.pem"], with_private_key(self.layout.secret_source))
        self.assertEqual([], with_private_key(self.layout.root))

    def test_controller_keystore_opens_in_java_with_its_password_file(self):
        """Winstone не читает PEM; keystore, который Java не открывает, остановил бы контроллер при старте."""
        keytool = shutil.which("keytool")
        if keytool is None or subprocess.run([keytool, "-help"], capture_output=True).returncode:
            self.skipTest("keytool is not available")
        ci.initialize(self.layout)
        listing = subprocess.run(
            [keytool, "-list", "-v", "-keystore", str(self.layout.controller_secrets / "tls_keystore.p12"),
             "-storetype", "PKCS12", "-storepass:file", str(self.layout.controller_secrets / "keystore_password")],
            capture_output=True, text=True)
        self.assertEqual(0, listing.returncode, listing.stderr)
        self.assertIn("PrivateKeyEntry", listing.stdout)
        self.assertIn("Certificate chain length: 2", listing.stdout)

    @unittest.skipUnless(shutil.which("mkcert"), "mkcert is not available")
    def test_macos_uses_the_existing_ca_for_a_distinct_controller_leaf(self):
        """Выпуск на Mac использует только временный тестовый CAROOT, не создаёт CA и не меняет системное доверие."""
        root = Path(self.directory.name) / "fixture-ca"
        root.mkdir(mode=0o700)
        subprocess.run([ci.openssl_binary(), "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "30",
                        "-subj", "/CN=Jenkins test CA", "-addext", "basicConstraints=critical,CA:TRUE",
                        "-addext", "keyUsage=critical,keyCertSign,cRLSign", "-addext", "subjectKeyIdentifier=hash",
                        "-keyout", str(root / "rootCA-key.pem"), "-out", str(root / "rootCA.pem")],
                       check=True, capture_output=True)
        previous = {path.name: path.read_bytes() for path in root.iterdir()}
        with mock.patch.object(ci.sys, "platform", "darwin"), mock.patch.dict(os.environ, {"CAROOT": str(root)}):
            ci.initialize(self.layout)
            certificate = (self.layout.controller_secrets / "tls_certificate.pem").read_bytes()
            ci.initialize(self.layout)
        self.assertEqual(previous, {path.name: path.read_bytes() for path in root.iterdir()})
        self.assertEqual(previous["rootCA.pem"], (self.layout.agent_secrets / "tls_ca.pem").read_bytes())
        self.assertEqual(certificate, (self.layout.controller_secrets / "tls_certificate.pem").read_bytes())
        for name in ("localhost", "127.0.0.1", "::1", ci.CONTROLLER):
            self.handshake(name)

    def test_existing_tls_is_not_silently_reissued_after_material_corruption(self):
        """Чужой ключ или keystore должны остановить запуск, сохранив сертификат для восстановления."""
        ci.initialize(self.layout)
        certificate = (self.layout.controller_secrets / "tls_certificate.pem").read_bytes()
        foreign = ci.Layout(Path(self.directory.name) / "other-home", Path(self.directory.name) / "other-secrets")
        ci.initialize(foreign)
        for names in (("tls_private_key.pem",), ("tls_keystore.p12", "keystore_password")):
            with self.subTest(names=names):
                previous = {name: (self.layout.controller_secrets / name).read_bytes() for name in names}
                for name in names:
                    (self.layout.controller_secrets / name).write_bytes((foreign.controller_secrets / name).read_bytes())
                with self.assertRaisesRegex(ValueError, "Existing controller TLS"):
                    ci.initialize(self.layout)
                self.assertEqual(certificate, (self.layout.controller_secrets / "tls_certificate.pem").read_bytes())
                for name, contents in previous.items():
                    (self.layout.controller_secrets / name).write_bytes(contents)

    def test_rejects_a_hand_edited_admin_password(self):
        ci.initialize(self.layout)
        (self.layout.controller_secrets / "admin_password").write_text("short\n")
        with self.assertRaisesRegex(ValueError, "admin password"):
            ci.initialize(self.layout)


class ConfinementTest(unittest.TestCase):
    """У агента свой демон; ни один контейнер не должен дотянуться до сокета Docker хоста и учётных данных разработчика."""

    def setUp(self):
        self.layout = ci.Layout(Path("/var/tmp/jenkins-home"), Path("/var/tmp/jenkins-secrets"))
        self.arguments = {
            ci.DAEMON: ci.daemon_arguments(),
            ci.CONTROLLER: ci.controller_arguments(self.layout, "controller-image", ci.DEFAULT_CONTROLLER_PORT),
            ci.AGENT: ci.agent_arguments(self.layout, "agent-image"),
        }

    def option_values(self, name: str, option: str) -> list[str]:
        arguments = self.arguments[name]
        return [arguments[index + 1] for index, value in enumerate(arguments) if value == option]

    def mounts(self, name: str) -> list[dict]:
        return [dict(part.partition("=")[::2] for part in value.split(","))
                for value in self.option_values(name, "--mount")]

    def test_only_the_docker_daemon_is_privileged(self):
        self.assertEqual([ci.DAEMON], [name for name, arguments in self.arguments.items() if "--privileged" in arguments])
        for name in (ci.CONTROLLER, ci.AGENT):
            self.assertIn("no-new-privileges", self.arguments[name])
            self.assertEqual("ALL", self.arguments[name][self.arguments[name].index("--cap-drop") + 1])

    def test_only_the_controller_publishes_a_port_and_only_on_loopback(self):
        self.assertEqual([], self.option_values(ci.DAEMON, "--publish") + self.option_values(ci.AGENT, "--publish"))
        published = self.option_values(ci.CONTROLLER, "--publish")
        self.assertTrue(published and all(port.startswith("127.0.0.1:") for port in published))

    def test_host_paths_are_limited_to_the_read_only_source_snapshot(self):
        for name in self.arguments:
            binds = [mount for mount in self.mounts(name) if mount["type"] == "bind"]
            self.assertTrue(all(mount["src"] == str(self.layout.source) and "readonly" in mount for mount in binds), name)
            self.assertFalse(any("docker.sock" in argument for argument in self.arguments[name]), name)
            # Источник секретов не монтируется: значения попадают в тома через stdin и только нужной роли.
            self.assertFalse(any(str(self.layout.secret_source) in argument for argument in self.arguments[name]), name)

    def test_secrets_are_split_by_role(self):
        sources = {name: {mount["src"] for mount in self.mounts(name)} for name in self.arguments}
        self.assertIn(ci.VOLUME_CONTROLLER_SECRETS, sources[ci.CONTROLLER])
        self.assertNotIn(ci.VOLUME_AGENT_SECRETS, sources[ci.CONTROLLER])
        self.assertIn(ci.VOLUME_AGENT_SECRETS, sources[ci.AGENT])
        self.assertNotIn(ci.VOLUME_CONTROLLER_SECRETS, sources[ci.AGENT])
        self.assertNotIn(ci.VOLUME_AGENT_SECRETS, sources[ci.DAEMON])

    def test_agent_uses_the_private_daemon_over_tls_and_keeps_the_work_path(self):
        agent = self.arguments[ci.AGENT]
        self.assertIn(f"container:{ci.DAEMON}", agent)
        self.assertIn("DOCKER_TLS_VERIFY=1", agent)
        # kind разрешает источники bind mount на стороне демона, поэтому оба контейнера видят рабочий том по одному пути.
        for name in (ci.AGENT, ci.DAEMON):
            self.assertIn({"type": "volume", "src": ci.VOLUME_AGENT, "dst": ci.AGENT_ROOT}, self.mounts(name))


if __name__ == "__main__":
    os.umask(0o077)
    unittest.main()
