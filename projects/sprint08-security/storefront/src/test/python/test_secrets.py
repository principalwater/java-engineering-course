"""Граница хранения локальных секретов: права, повторный запуск и отказ от опасных путей."""

import importlib.util
from pathlib import Path
import tempfile
import unittest

specification = importlib.util.spec_from_file_location(
    "store_secrets", Path(__file__).resolve().parents[4] / "scripts/init-secrets.py")
store_secrets = importlib.util.module_from_spec(specification)
specification.loader.exec_module(store_secrets)


class SecretStorageTest(unittest.TestCase):
    def test_initialization_preserves_values_and_repairs_permissions(self):
        with tempfile.TemporaryDirectory() as root:
            repository = Path(root) / "repository"
            directory = Path(root) / "secrets"
            store_secrets.initialize(directory, repository)
            original = {path.name: path.read_bytes() for path in directory.iterdir()}
            self.assertTrue(all(value.strip() for value in original.values()))
            self.assertNotIn(original["redis_password"].strip(), original["redis_users.acl"])
            for path in directory.iterdir():
                path.chmod(0o644)
            store_secrets.initialize(directory, repository)
            self.assertEqual(original, {path.name: path.read_bytes() for path in directory.iterdir()})
            self.assertEqual(0o700, directory.stat().st_mode & 0o777)
            self.assertTrue(all(path.stat().st_mode & 0o777 == 0o600 for path in directory.iterdir()))

    def test_rejects_tracked_paths_symlinks_and_empty_existing_values(self):
        with tempfile.TemporaryDirectory() as root:
            repository = Path(root) / "repository"
            with self.assertRaisesRegex(ValueError, "outside"):
                store_secrets.initialize(repository / "secrets", repository)
            self.assertFalse(repository.exists())
            directory = Path(root) / "secrets"
            directory.mkdir()
            link = Path(root) / "link"
            link.symlink_to(directory, target_is_directory=True)
            with self.assertRaisesRegex(ValueError, "symlink"):
                store_secrets.initialize(link, repository)
            (directory / "db_password").write_bytes(b"\n")
            with self.assertRaisesRegex(ValueError, "non-empty"):
                store_secrets.initialize(directory, repository)
            (directory / "db_password").unlink()
            (directory / "redis_users.acl").symlink_to(Path(root) / "outside")
            with self.assertRaisesRegex(ValueError, "symlink"):
                store_secrets.initialize(directory, repository)
            self.assertFalse((Path(root) / "outside").exists())
