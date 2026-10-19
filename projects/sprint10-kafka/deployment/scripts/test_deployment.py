"""Проверки сохранности секретов и настоящего TLS без изменения локального CA."""

import importlib.util
import os
from pathlib import Path
import ssl
import stat
import subprocess
import tempfile
import unittest
from unittest.mock import patch


def load_script(name):
    specification = importlib.util.spec_from_file_location(name.replace("-", "_"), Path(__file__).with_name(name + ".py"))
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


secrets_module = load_script("init-secrets")
tls = load_script("init-tls")


class SecretFilesTest(unittest.TestCase):
    def test_initialization_preserves_values_and_private_permissions(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            repository = root / "repository"
            directory = secrets_module.initialize(root / "secrets", repository)
            initial = {path.name: path.read_bytes() for path in directory.iterdir()}
            secrets_module.initialize(directory, repository)
            self.assertEqual(initial, {path.name: path.read_bytes() for path in directory.iterdir()})
            self.assertEqual(0o700, stat.S_IMODE(directory.stat().st_mode))
            for path in directory.iterdir():
                self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))
            self.assertEqual(len(initial), len(set(initial.values())))

    def test_rejects_repository_symlinks_and_malformed_existing_secrets(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            repository = root / "repository"
            with self.assertRaises(ValueError):
                secrets_module.initialize(repository / "secrets", repository)
            directory = secrets_module.initialize(root / "secrets", repository)
            secret = directory / secrets_module.SECRET_NAMES[0]
            secret.write_text("invalid value\n")
            with self.assertRaises(ValueError):
                secrets_module.initialize(directory, repository)
            original = root / "original"
            original.write_text("preserve me")
            secret.unlink()
            secret.symlink_to(original)
            with self.assertRaises(ValueError):
                secrets_module.initialize(directory, repository)
            self.assertEqual("preserve me", original.read_text())

    def test_linux_file_source_requires_explicit_isolated_test_opt_in(self):
        with patch.object(secrets_module.sys, "platform", "linux"), patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(RuntimeError):
                secrets_module.secret_directory(Path("/repository"))
            os.environ["BANK_ALLOW_PLAINTEXT_TEST_SECRETS"] = "true"
            os.environ["BANK_SECRETS_DIR"] = "/isolated-test-secrets"
            self.assertEqual("/isolated-test-secrets", secrets_module.secret_directory(Path("/repository")))


class LocalTlsTest(unittest.TestCase):
    def test_real_certificate_matches_bank_hosts_and_preserves_existing_key(self):
        openssl = tls.openssl_binary()
        with tempfile.TemporaryDirectory() as temporary, patch.object(tls.sys, "platform", "linux"), patch.object(tls, "openssl_binary", return_value=openssl):
            directory = tls.initialize(Path(temporary) / "tls")
            initial = {path.name: path.read_bytes() for path in directory.iterdir()}
            tls.initialize(directory)
            self.assertEqual(initial, {path.name: path.read_bytes() for path in directory.iterdir()})
            ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER).load_cert_chain(directory / "tls_certificate.pem", directory / "tls_private_key.pem")
            wrong_host = subprocess.run([openssl, "verify", "-CAfile", str(directory / "tls_ca.pem"), "-verify_hostname", "other.localhost", str(directory / "tls_certificate.pem")], capture_output=True)
            self.assertNotEqual(0, wrong_host.returncode)
            (directory / "tls_private_key.pem").unlink()
            with self.assertRaises(ValueError):
                tls.initialize(directory)


if __name__ == "__main__":
    unittest.main()
