"""Проверяем реальный trust chain и повторный выпуск, а не текст конфигурации прокси."""

import hashlib
import importlib.util
from pathlib import Path
import ssl
import subprocess
import tempfile
import unittest
from unittest.mock import patch


specification = importlib.util.spec_from_file_location(
    "store_tls", Path(__file__).resolve().parents[4] / "scripts/init-tls.py")
store_tls = importlib.util.module_from_spec(specification)
specification.loader.exec_module(store_tls)


class LocalTlsTest(unittest.TestCase):
    def test_certificates_match_both_hosts_and_keep_the_existing_key(self):
        with tempfile.TemporaryDirectory() as root:
            directory = Path(root) / "secrets"
            with patch.object(store_tls.sys, "platform", "linux"):
                store_tls.initialize(directory)
            original = {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in directory.iterdir()}
            store_tls.initialize(directory)
            self.assertEqual(original, {path.name: hashlib.sha256(path.read_bytes()).hexdigest()
                                        for path in directory.iterdir()})
            # load_cert_chain проверяет также соответствие приватного ключа сертификату.
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            context.load_cert_chain(directory / "tls_certificate.pem", directory / "tls_private_key.pem")
            rejected = subprocess.run(["openssl", "verify", "-CAfile", str(directory / "tls_ca.pem"),
                                       "-verify_hostname", "foreign.example.test", str(directory / "tls_certificate.pem")],
                                      capture_output=True)
            self.assertNotEqual(0, rejected.returncode)
            self.assertTrue(all(path.stat().st_mode & 0o777 == 0o600 for path in directory.iterdir()))
            (directory / "tls_private_key.pem").write_bytes(b"invalid private key")
            with self.assertRaises(ssl.SSLError):
                store_tls.initialize(directory)

    def test_refuses_incomplete_material_and_symlink_without_overwriting(self):
        with tempfile.TemporaryDirectory() as root:
            directory = Path(root) / "secrets"
            directory.mkdir()
            key = directory / "tls_private_key.pem"
            key.write_bytes(b"preserve me")
            with self.assertRaisesRegex(ValueError, "incomplete"):
                store_tls.initialize(directory)
            self.assertEqual(b"preserve me", key.read_bytes())
            key.unlink()
            key.symlink_to(Path(root) / "outside")
            with self.assertRaisesRegex(ValueError, "symlink"):
                store_tls.initialize(directory)
            self.assertFalse((Path(root) / "outside").exists())
