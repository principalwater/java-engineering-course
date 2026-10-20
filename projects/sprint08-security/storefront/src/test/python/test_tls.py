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
    def test_ca_key_migration_resumes_after_copy_without_changing_the_key(self):
        with tempfile.TemporaryDirectory() as root:
            ca = Path(root) / "ca"
            ca.mkdir()
            source = ca / "rootCA-key.pem"
            source.write_bytes(b"private CA fixture")
            encrypted = Path(root) / "encrypted"
            target_directory = encrypted / "local-ca"
            target_directory.mkdir(parents=True)
            target = target_directory / source.name
            target.write_bytes(source.read_bytes())
            target.chmod(0o400)
            with patch.object(store_tls.subprocess, "check_output", return_value=str(ca) + "\n"):
                store_tls.protect_mkcert_key(encrypted)
                store_tls.protect_mkcert_key(encrypted)
            self.assertTrue(source.is_symlink())
            self.assertEqual(b"private CA fixture", source.read_bytes())
            self.assertEqual(0o400, target.stat().st_mode & 0o777)

    def test_certificates_match_both_hosts_and_keep_the_existing_key(self):
        with tempfile.TemporaryDirectory() as root:
            directory = Path(root) / "secrets"
            with patch.object(store_tls.sys, "platform", "linux"):
                store_tls.initialize(directory)
            original = {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in directory.iterdir()}
            store_tls.initialize(directory)
            self.assertEqual(original, {path.name: hashlib.sha256(path.read_bytes()).hexdigest()
                                        for path in directory.iterdir()})
            with patch.object(store_tls, "HOSTS", store_tls.HOSTS + ("extra.valgrind-home.localhost",)):
                with self.assertRaises(subprocess.CalledProcessError):
                    store_tls.initialize(directory)
            # load_cert_chain проверяет также соответствие приватного ключа сертификату.
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            context.load_cert_chain(directory / "tls_certificate.pem", directory / "tls_private_key.pem")
            for host in store_tls.HOSTS:
                subprocess.run([store_tls.openssl_binary(), "verify", "-x509_strict", "-CAfile", str(directory / "tls_ca.pem"),
                                "-verify_hostname", host, str(directory / "tls_certificate.pem")],
                               check=True, capture_output=True)
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
