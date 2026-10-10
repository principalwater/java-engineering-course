"""Registry failures cannot remove digest pinning or authorize an unavailable image."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "scripts/prepare-project-ci.py"


class PrepareProjectCiTest(unittest.TestCase):
    def invoke(self, directory, fail_all=False):
        project = directory / "project"
        project.mkdir()
        image = "postgres:17-alpine@sha256:" + "a" * 64
        (project / "compose.yaml").write_text("services:\n  web:\n    image: " + image + "\n")
        command = directory / "docker"
        command.write_text('''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
with Path(os.environ["PULL_CALLS"]).open("a") as output:
    output.write(json.dumps(sys.argv[1:]) + "\\n")
if os.environ["FAIL_ALL"] == "1" or sys.argv[2].startswith("public.ecr.aws/"):
    print("Registry rate limit exceeded", file=sys.stderr)
    sys.exit(1)
''')
        command.chmod(0o755)
        environment = directory / "github-env"
        calls = directory / "calls.jsonl"
        result = subprocess.run(["python3", str(SCRIPT), "--exercise"], cwd=project,
                                env={**os.environ, "PATH": str(directory) + os.pathsep + os.environ["PATH"],
                                     "RUNNER_TEMP": str(directory / "runner"), "GITHUB_ENV": str(environment),
                                     "PULL_CALLS": str(calls), "FAIL_ALL": "1" if fail_all else "0"},
                                capture_output=True, text=True, timeout=10)
        attempted = [json.loads(line) for line in calls.read_text().splitlines()]
        return result, image, attempted, environment

    def test_rate_limited_mirror_falls_back_without_changing_the_digest(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            result, image, attempted, environment = self.invoke(directory)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual([["pull", "public.ecr.aws/docker/library/" + image], ["pull", image]], attempted)
            rendered = json.loads((directory / "runner/project-compose/compose.json").read_text())
            self.assertEqual(image, rendered["services"]["web"]["image"])
            self.assertIn("COMPOSE_FILE=", environment.read_text())

    def test_failure_of_both_registries_does_not_publish_a_compose_override(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            result, image, attempted, environment = self.invoke(directory, fail_all=True)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual(2, len(attempted))
            self.assertFalse(environment.exists())
            self.assertFalse((directory / "runner/project-compose/compose.json").exists())


if __name__ == "__main__":
    unittest.main()
