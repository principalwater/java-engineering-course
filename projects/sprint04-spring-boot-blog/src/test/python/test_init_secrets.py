"""Контракт CLI: подготовка секретов не меняет действующие пароли и не раскрывает их."""

import os
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest


MODULE = Path(__file__).resolve().parents[3]
SECRET_NAMES = {"db_password", "analytics_db_password", "clickhouse_password"}


class InitSecretsTest(unittest.TestCase):
    def invoke(self, directory):
        return subprocess.run(
            ["python3", str(MODULE / "scripts/init-secrets.py")],
            env={**os.environ, "BLOG_SECRETS_DIR": str(directory)},
            capture_output=True, text=True, timeout=10,
        )

    def test_prepares_private_files_without_disclosure_or_password_rotation(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / "secrets"
            result = self.invoke(directory)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(0o700, stat.S_IMODE(directory.stat().st_mode))
            contents = {file.name: file.read_bytes() for file in directory.iterdir()}
            self.assertEqual(SECRET_NAMES, contents.keys())
            for name, value in contents.items():
                self.assertEqual(0o600, stat.S_IMODE((directory / name).stat().st_mode))
                self.assertTrue(value.strip())
                self.assertNotIn(value.decode().strip(), result.stdout + result.stderr)

            self.assertEqual(0, self.invoke(directory).returncode)
            self.assertEqual(contents, {file.name: file.read_bytes() for file in directory.iterdir()})

    def test_rejects_empty_files_and_symlinks_without_changing_their_target(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / "secrets"
            directory.mkdir()
            password = directory / "db_password"
            password.touch()
            self.assertNotEqual(0, self.invoke(directory).returncode)
            self.assertEqual(b"", password.read_bytes())

            target = Path(temporary) / "existing-password"
            target.write_text("unchanged")
            password.unlink()
            password.symlink_to(target)
            self.assertNotEqual(0, self.invoke(directory).returncode)
            self.assertEqual("unchanged", target.read_text())


if __name__ == "__main__":
    unittest.main()
