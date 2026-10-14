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
    def test_saved_plan_requires_the_same_two_artifacts_sources_and_builder(self):
        cases = ["unchanged", "storefront artifact", "payments artifact", "source", "builder",
                 "documentation", "packaged Markdown", "infrastructure runtime config"]
        for case in cases:
            with self.subTest(case=case), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                repository = root / "repository"
                infrastructure = repository / "infra"
                infrastructure.mkdir(parents=True)
                (infrastructure / ".terraform.lock.hcl").write_text("locked")
                (infrastructure / "deploy.py").write_text("driver")
                (infrastructure / "redis.conf").write_text("maxmemory 32mb")
                project = repository / "project"
                artifacts = project / "build/deployment"
                artifacts.mkdir(parents=True)
                (project / "build.gradle").write_text("project")
                markdown = project / "storefront/src/main/resources/README.md"
                markdown.parent.mkdir(parents=True)
                markdown.write_text("packaged")
                for service in ["storefront", "payments"]:
                    (artifacts / (service + ".jar")).write_bytes(service.encode())
                calls = []

                def execute(command, **options):
                    calls.append(command)
                    for option in command:
                        if option.startswith("-out="):
                            Path(option.removeprefix("-out=")).write_bytes(b"saved plan")

                def output(command, **options):
                    if command[0] == "docker":
                        return "unix:///var/run/docker.sock\n"
                    return b'{"resource_changes": []}'

                environment = {"COURSE_DEPLOY_STATE": str(root / "state"),
                               "COURSE_DEPLOY_SECRETS": str(root / "secrets")}
                with patch.object(deploy, "REPOSITORY", repository), patch.object(deploy, "INFRA", infrastructure), \
                     patch.object(deploy, "STACKS", {"sprint07-h2": "project"}), \
                     patch.dict(os.environ, environment), patch.object(deploy, "prepare") as prepare, \
                     patch.object(deploy.subprocess, "run", side_effect=execute), \
                     patch.object(deploy.subprocess, "check_output", side_effect=output):
                    with patch.object(sys, "argv", ["deploy.py", "sprint07-h2", "plan"]):
                        deploy.main()
                    prepare.assert_called_once()
                    receipt = root / "state/sprint07-h2/plan-inputs.json"
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
                    arguments = ["deploy.py", "sprint07-h2", "apply"]
                    if case == "builder":
                        arguments += ["--build-tool", "maven"]
                    with patch.object(sys, "argv", arguments):
                        if case in {"unchanged", "documentation"}:
                            deploy.main()
                            self.assertFalse(receipt.exists())
                        else:
                            with self.assertRaisesRegex(ValueError, "inputs changed"):
                                deploy.main()
                    self.assertEqual(1 if case in {"unchanged", "documentation"} else 0,
                                     sum("apply" in command for command in calls))
                    # Applying the saved plan must never invoke the builder again.
                    prepare.assert_called_once()

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
