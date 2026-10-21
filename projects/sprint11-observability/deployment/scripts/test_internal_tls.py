"""Проверка выпуска, взаимного доверия и сохранности реальных ключей внутренних сервисов."""

import importlib.util
from pathlib import Path
import ssl
import subprocess
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location("internal_tls", Path(__file__).with_name("init-internal-tls.py"))
TLS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TLS)


class InternalTlsTest(unittest.TestCase):
    def test_distinct_service_keys_validate_only_the_intended_hostname_and_survive_rerun(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            TLS.initialize(directory, ("accounts", "cash"))
            before = {path.name: path.read_bytes() for path in directory.iterdir()}
            self.assertNotEqual(before["internal_accounts_key.pem"], before["internal_cash_key.pem"])
            context = ssl.create_default_context(cafile=str(directory / "internal_ca.pem"))
            self.assertEqual(context.verify_mode, ssl.CERT_REQUIRED)
            openssl = TLS.module("local_tls", "init-tls.py").openssl_binary()
            wrong = subprocess.run([openssl, "verify", "-x509_strict", "-CAfile", str(directory / "internal_ca.pem"),
                                    "-verify_hostname", "cash", str(directory / "internal_accounts_certificate.pem")],
                                   capture_output=True)
            self.assertNotEqual(wrong.returncode, 0)
            TLS.initialize(directory, ("accounts", "cash"))
            self.assertEqual(before, {path.name: path.read_bytes() for path in directory.iterdir()})
            self.assertTrue(all(path.stat().st_mode & 0o077 == 0 for path in directory.iterdir()))
            (directory / "internal_accounts_keystore_password").write_text("incorrect-password\n")
            with self.assertRaisesRegex(RuntimeError, "TLS phase failed"):
                TLS.initialize(directory, ("accounts",))
            self.assertEqual((directory / "internal_accounts_key.pem").read_bytes(), before["internal_accounts_key.pem"])


if __name__ == "__main__":
    unittest.main()
