"""A promtool or amtool archive that fails the vendor checksum must never reach PATH or the tools directory."""

import hashlib
import io
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "scripts/setup-observability.sh"
RELEASES = {"prometheus": ("3.15.0", "promtool"), "alertmanager": ("0.34.1", "amtool")}

# The stand-in serves the files that the vendor release URLs map to; checksum verification stays in the script.
FAKE_CURL = """#!/usr/bin/env python3
import shutil, sys
from pathlib import Path
arguments = sys.argv[1:]
source = Path(__file__).with_name("releases") / arguments[-1].removeprefix("https://github.com/prometheus/").replace("/releases/download", "")
shutil.copyfile(source, arguments[arguments.index("--output") + 1])
"""


def publish_release(directory, repository, architecture="amd64", tamper=None):
    version, binary = RELEASES[repository]
    stem = f"{repository}-{version}.linux-{architecture}"
    script = b"#!/bin/sh\n"
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w:gz") as tar:
        member = tarfile.TarInfo(f"{stem}/{binary}")
        member.size, member.mode = len(script), 0o755
        tar.addfile(member, io.BytesIO(script))
    release = directory / "releases" / repository / f"v{version}"
    release.mkdir(parents=True)
    (release / f"{stem}.tar.gz").write_bytes(buffer.getvalue())
    digest = {None: hashlib.sha256(buffer.getvalue()).hexdigest(), "wrong-digest": "1" * 64, "missing-line": None}[tamper]
    # The sibling architecture is listed first: only the exact archive name may be verified.
    other_architecture = "arm64" if architecture == "amd64" else "amd64"
    listing = "0" * 64 + f"  {stem.replace(architecture, other_architecture)}.tar.gz\n"
    (release / "sha256sums.txt").write_text(listing + (f"{digest}  {stem}.tar.gz\n" if digest else ""))


class SetupObservabilityTest(unittest.TestCase):
    def install(self, failing=None, tamper=None, architecture="amd64", machine=None):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            for repository in RELEASES:
                publish_release(directory, repository, architecture, tamper if repository == failing else None)
            uname = directory / "uname"
            reported_machine = machine or {"amd64": "x86_64", "arm64": "aarch64"}[architecture]
            uname.write_text('#!/bin/sh\ncase "$1" in -s) echo Linux;; -m) echo ' + reported_machine + ';; esac\n')
            uname.chmod(0o755)
            curl = directory / "curl"
            curl.write_text(FAKE_CURL)
            curl.chmod(0o755)
            runner, github_path = directory / "runner", directory / "github-path"
            runner.mkdir()
            result = subprocess.run(["bash", str(SCRIPT)], capture_output=True, text=True, timeout=30,
                                    env={**os.environ, "PATH": str(directory) + os.pathsep + os.environ["PATH"],
                                         "RUNNER_TEMP": str(runner), "GITHUB_PATH": str(github_path)})
            installed = sorted(path.name for path in (runner / "bank11-tools/bin").glob("*"))
            return result, installed, github_path.exists()

    def test_verified_archives_install_both_checkers_and_publish_the_tools_directory(self):
        for architecture in ("amd64", "arm64"):
            with self.subTest(architecture=architecture):
                result, installed, published = self.install(architecture=architecture)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(["amtool", "promtool"], installed)
                self.assertTrue(published)

    def test_unsupported_architecture_is_rejected_before_download_or_path_publication(self):
        result, installed, published = self.install(machine="riscv64")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], installed)
        self.assertFalse(published)

    def test_checksum_failure_or_missing_vendor_line_stops_before_publishing_tools(self):
        for failing in RELEASES:
            for tamper in ("wrong-digest", "missing-line"):
                with self.subTest(failing=failing, tamper=tamper):
                    result, installed, published = self.install(failing, tamper)
                    self.assertNotEqual(0, result.returncode)
                    self.assertNotIn(RELEASES[failing][1], installed)
                    self.assertFalse(published)


if __name__ == "__main__":
    unittest.main()
