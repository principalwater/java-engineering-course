"""Operational safety contracts at the saved-plan boundary."""

import copy
import importlib.util
import os
from pathlib import Path
import tempfile
import sys
import unittest
from unittest.mock import patch

specification = importlib.util.spec_from_file_location("deploy", Path(__file__).parents[1] / "deploy.py")
deploy = importlib.util.module_from_spec(specification)
specification.loader.exec_module(deploy)


class DeploymentPlanTest(unittest.TestCase):
    def test_saved_plan_requires_the_same_artifacts_and_sources(self):
        common = ["unchanged", "source", "documentation", "packaged Markdown", "infrastructure runtime config", "authorization mount", "rendered runtime config"]
        services = {"sprint07-h2": ["storefront", "payments"], "sprint08-h2": ["storefront", "payments"],
                    "sprint09": ["accounts", "cash", "transfer", "notifications", "front", "gateway", "analytics"]}
        for stack, case in [(stack, case) for stack, names in services.items() for case in common + [name + " artifact" for name in names]]:
            with self.subTest(stack=stack, case=case), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                repository = root / "repository"
                infrastructure = repository / "infra"
                infrastructure.mkdir(parents=True)
                (infrastructure / ".terraform.lock.hcl").write_text("locked")
                (infrastructure / "deploy.py").write_text("driver")
                (infrastructure / "redis.conf").write_text("maxmemory 32mb")
                project = root / "bank-project" if stack == "sprint09" else repository / "project"
                artifacts = project / "build/deployment"
                artifacts.mkdir(parents=True)
                (project / "build.gradle").write_text("project")
                realm = project / "authorization/storefront-api-realm.json"
                realm.parent.mkdir()
                realm.write_text("realm")
                markdown = project / "storefront/src/main/resources/README.md"
                markdown.parent.mkdir(parents=True)
                markdown.write_text("packaged")
                for service in services[stack]:
                    (artifacts / (service + ".jar")).write_bytes(service.encode())
                calls = []

                def execute(command, **options):
                    calls.append(command)
                    for option in command:
                        if option.startswith("-out="):
                            Path(option.removeprefix("-out=")).write_bytes(b"saved plan")
                    if "apply" in command:
                        for name in ["terraform.tfstate", "terraform.tfstate.backup"]:
                            state_file = root / "state" / stack / name
                            state_file.write_text('{"resources":[]}')
                            state_file.chmod(0o644)

                def output(command, **options):
                    if command[0] == "docker":
                        return "unix:///var/run/docker.sock\n"
                    return b'{"resource_changes": []}'

                environment = {"COURSE_DEPLOY_STATE": str(root / "state"),
                               "COURSE_DEPLOY_SECRETS": str(root / "secrets")}
                with patch.object(deploy, "REPOSITORY", repository), patch.object(deploy, "INFRA", infrastructure), \
                     patch.object(deploy, "STACKS", {stack: "project"}), \
                     patch.dict(os.environ, environment), patch.object(deploy, "prepare") as prepare, \
                     patch.object(deploy.subprocess, "run", side_effect=execute), \
                     patch.object(deploy.subprocess, "check_output", side_effect=output):
                    selected = ["--project-dir", str(project)] if stack == "sprint09" else []
                    with patch.object(sys, "argv", ["deploy.py", stack, "plan"] + selected):
                        deploy.main()
                    prepare.assert_called_once()
                    receipt = root / "state" / stack / "plan-inputs.json"
                    if case.endswith("artifact"):
                        (artifacts / (case.split()[0] + ".jar")).write_bytes(b"changed executable")
                    elif case == "source":
                        (project / "build.gradle").write_text("changed source")
                    elif case == "documentation":
                        (project / "README.md").write_text("clarified deployment instructions")
                    elif case == "packaged Markdown":
                        markdown.write_text("changed executable resource")
                    elif case == "infrastructure runtime config":
                        (infrastructure / "redis.conf").write_text("maxmemory 64mb")
                    elif case == "authorization mount":
                        realm.write_text("changed realm")
                    elif case == "rendered runtime config":
                        if stack == "sprint09":
                            runtime = root / "state" / stack / "runtime"
                            runtime.mkdir()
                            (runtime / "Caddyfile").write_text("changed rendered TLS settings")
                        else:
                            (infrastructure / "redis.conf").write_text("changed runtime settings")
                    arguments = ["deploy.py", stack, "apply"] + selected
                    with patch.object(sys, "argv", arguments):
                        if case in {"unchanged", "documentation"}:
                            deploy.main()
                            self.assertFalse(receipt.exists())
                            for name in ["terraform.tfstate", "terraform.tfstate.backup"]:
                                self.assertEqual(0o600, (receipt.parent / name).stat().st_mode & 0o777)
                        else:
                            with self.assertRaisesRegex(ValueError, "inputs changed"):
                                deploy.main()
                    self.assertEqual(1 if case in {"unchanged", "documentation"} else 0,
                                     sum("apply" in command for command in calls))
                    # Applying the saved plan must never invoke the builder again.
                    prepare.assert_called_once()

    def test_bank_yaml_merge_and_public_tls_templates_are_normalized_without_compose(self):
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory) / "project"
            (project / "edge").mkdir(parents=True)
            (project / "authorization").mkdir()
            (project / "edge/Caddyfile").write_text("https://bank.valgrind-home.localhost:8449 { header_up X-Forwarded-Port 8449 }")
            (project / "authorization/bank-realm.json").write_text('{"redirectUris":["https://bank.valgrind-home.localhost:8449/login"],"secret":"__oauth_front_secret__"}')
            (project / "compose.yaml").write_text("x-base: &base {ISSUER: canonical, TIMEOUT: 1s}\nx-extra: &extra {MODE: jdbc}\nservices:\n  accounts:\n    environment:\n      <<: [*base, *extra]\n      TIMEOUT: 3s\n")
            (project / "compose.analytics.yaml").write_text("services: {}\n")
            runtime = Path(directory) / "runtime"
            deploy.prepare_bank_configuration(project, runtime)
            import json
            settings = json.loads((runtime / "compose.json").read_text())["services"]["accounts"]["environment"]
            self.assertEqual({"ISSUER": "canonical", "TIMEOUT": "3s", "MODE": "jdbc"}, settings)
            self.assertIn(":28450", (runtime / "Caddyfile").read_text())
            self.assertIn("X-Forwarded-Port 28450", (runtime / "Caddyfile").read_text())
            realm = json.loads((runtime / "bank-realm.json").read_text())
            self.assertEqual(["https://bank.valgrind-home.localhost:28450/login"], realm["redirectUris"])
            self.assertEqual("__oauth_front_secret__", realm["secret"])
            (project / "authorization/bank-realm.json").write_text('{"secret":"synthetic-secret-value"}')
            with self.assertRaisesRegex(ValueError, "secret file value"):
                deploy.prepare_bank_configuration(project, runtime, {"synthetic-secret-value"})
            (project / "compose.yaml").write_text("!!python/object:unsafe {}")
            with self.assertRaisesRegex(ValueError, "safe YAML"):
                deploy.prepare_bank_configuration(project, runtime)

    def test_plan_rejects_exposure_and_unowned_changes(self):
        plan = {"resource_changes": [{"type": "docker_container", "change": {
            "actions": ["create"], "after": {
                "name": "java-tofu-sprint04-backend", "privileged": False,
                "env": ["SPRING_PROFILES_ACTIVE=postgresql"],
                "ports": [{"ip": "127.0.0.1", "external": 29084}],
                "volumes": [{"container_path": "/run/secrets/spring.datasource.password",
                             "host_path": "/tmp/external-course-secret", "read_only": True}],
            },
        }}]}
        deploy.check_plan(plan, "sprint04")
        cases = [
            ("public port", lambda value: value["ports"][0].update(ip="0.0.0.0")),
            ("privileged process", lambda value: value.update(privileged=True)),
            ("password in state", lambda value: value["env"].append("POSTGRES_PASSWORD=secret")),
            ("foreign stack", lambda value: value.update(name="someone-elses-db")),
            ("similar foreign prefix", lambda value: value.update(name="java-tofu-sprint040-db")),
            ("writable secret", lambda value: value["volumes"][0].update(read_only=False)),
            ("tracked secret", lambda value: value["volumes"][0].update(host_path=str(deploy.REPOSITORY / "password"))),
        ]
        for name, mutate in cases:
            with self.subTest(name=name):
                unsafe = copy.deepcopy(plan)
                mutate(unsafe["resource_changes"][0]["change"]["after"])
                with self.assertRaises(ValueError):
                    deploy.check_plan(unsafe, "sprint04")
        with self.assertRaisesRegex(ValueError, "outside this stack"):
            deploy.check_plan({"resource_changes": [{"type": "docker_container", "change": {
                "actions": ["delete"], "before": {"name": "foreign-db"}, "after": None,
            }}]}, "sprint04")

    def test_plan_rejects_secret_values_in_any_attribute_and_secret_named_settings(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "oauth_client_secret").write_text("0123456789abcdef0123456789abcdef\n")
            (root / "short").write_text("x\n")
            (root / "link").symlink_to(root / "oauth_client_secret")
            known = deploy.secret_values(root)
            self.assertEqual({"0123456789abcdef0123456789abcdef"}, known)

            def plan(**attributes):
                return {"resource_changes": [{"type": "docker_container", "change": {
                    "actions": ["create"], "after": {"name": "java-tofu-sprint08-h2-authorization", **attributes}}}]}

            deploy.check_plan(plan(env=["KC_DB_USERNAME=authorization", "PAYMENTS_OAUTH_TOKEN_URI=http://authorization:8080/token"]),
                              "sprint08-h2", known)
            for name, unsafe in [
                ("secret copied into a label", plan(labels=[{"label": "note", "value": "0123456789abcdef0123456789abcdef"}])),
                ("secret copied into a command", plan(command=["--client-secret=0123456789abcdef0123456789abcdef"])),
                ("client secret variable", plan(env=["STOREFRONT_CLIENT_SECRET=from-a-file"])),
                ("database password variable", plan(env=["KC_DB_PASSWORD=from-a-file"])),
            ]:
                with self.subTest(name=name), self.assertRaisesRegex(ValueError, "[Ss]ecret"):
                    deploy.check_plan(unsafe, "sprint08-h2", known)
            with self.assertRaisesRegex(ValueError, "secret file value"):
                deploy.check_secret_values({"pem": "BEGIN\nsynthetic-key-material\nEND"}, {"BEGIN\nsynthetic-key-material\nEND"})

    def test_oauth_stack_is_prepared_with_store_secrets_and_gradle_jars(self):
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            secrets_directory = repository.parent / "external-secrets"
            with patch.object(deploy, "REPOSITORY", repository):
                with self.assertRaisesRegex(ValueError, "unavailable in this checkout"):
                    deploy.prepare("sprint08-h2", secrets_directory, {})
                project = repository / "projects/sprint08-security"
                project.mkdir(parents=True)
                with patch.object(deploy.subprocess, "run") as run:
                    deploy.prepare("sprint08-postgresql", secrets_directory, {"PATH": "/usr/bin"})
            initialize, tls, package = run.call_args_list
            self.assertEqual("scripts/init-secrets.py", initialize.args[0][1])
            self.assertEqual(str(secrets_directory), initialize.kwargs["env"]["STORE_SECRETS_DIR"])
            self.assertEqual(project, initialize.kwargs["cwd"])
            self.assertEqual("scripts/init-tls.py", tls.args[0][1])
            self.assertEqual(str(secrets_directory), tls.kwargs["env"]["STORE_SECRETS_DIR"])
            self.assertEqual("scripts/package.py", package.args[0][1])

    def test_volume_deletion_cannot_pass_the_plan_gate(self):
        with self.assertRaisesRegex(ValueError, "persistent volumes"):
            deploy.check_plan({"resource_changes": [{"type": "docker_volume", "change": {
                "actions": ["delete"], "after": None,
            }}]}, "sprint04")

    def test_state_directory_inside_checkout_is_rejected_before_creation(self):
        destination = deploy.REPOSITORY / "forbidden-local-state"
        with self.assertRaisesRegex(ValueError, "outside the repository"):
            deploy.external_directory(destination)
        self.assertFalse(destination.exists())
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory) / "separate-bank-checkout"
            project.mkdir()
            state = project / "forbidden-state"
            with patch.dict(os.environ, {"COURSE_DEPLOY_STATE": str(state)}), \
                 patch.object(sys, "argv", ["deploy.py", "sprint09", "plan", "--project-dir", str(project)]), \
                 self.assertRaisesRegex(ValueError, "outside its source checkout"):
                deploy.main()
            self.assertFalse(state.exists())

    def test_external_password_is_private_preserved_and_validated(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(deploy.subprocess, "run"):
            path = Path(directory) / "db_password"
            deploy.prepare("docker-exercise", Path(directory), os.environ)
            original = path.read_bytes()
            self.assertTrue(original.strip())
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            deploy.prepare("docker-exercise", Path(directory), os.environ)
            self.assertEqual(path.read_bytes(), original)
            path.write_bytes(b"\n")
            with self.assertRaisesRegex(ValueError, "nonempty regular"):
                deploy.prepare("docker-exercise", Path(directory), os.environ)
            path.unlink()
            path.symlink_to(Path(directory) / "other")
            with self.assertRaisesRegex(ValueError, "nonempty regular"):
                deploy.prepare("docker-exercise", Path(directory), os.environ)
