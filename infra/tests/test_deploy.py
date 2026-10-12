"""Operational safety contracts at the saved-plan boundary."""

import copy
import importlib.util
from pathlib import Path
import unittest

specification = importlib.util.spec_from_file_location("deploy", Path(__file__).parents[1] / "deploy.py")
deploy = importlib.util.module_from_spec(specification)
specification.loader.exec_module(deploy)


class DeploymentPlanTest(unittest.TestCase):
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
