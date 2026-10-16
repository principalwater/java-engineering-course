"""Локальный TLS: приватный ключ остаётся рядом с Docker secrets, CA не попадает в контейнер."""

import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


HOSTS = ("shop.valgrind-home.localhost", "auth.valgrind-home.localhost")


def initialize(directory):
    directory = Path(directory).expanduser()
    if directory.is_symlink() or directory.resolve().is_relative_to(Path(__file__).resolve().parents[3]):
        raise ValueError("TLS material must remain outside the repository")
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    directory.chmod(0o700)
    certificate = directory / "tls_certificate.pem"
    key = directory / "tls_private_key.pem"
    authority = directory / "tls_ca.pem"
    for path in (certificate, key, authority):
        if path.is_symlink():
            raise ValueError("TLS files must not be symlinks")
    if any(path.exists() for path in (certificate, key, authority)):
        if not all(path.is_file() and path.stat().st_size for path in (certificate, key, authority)):
            raise ValueError("TLS files are incomplete; restore the certificate, key and CA together")
        subprocess.run(["openssl", "verify", "-CAfile", str(authority), str(certificate)],
                       check=True, stdout=subprocess.PIPE)
        for host in HOSTS:
            subprocess.run(["openssl", "x509", "-in", str(certificate), "-noout", "-checkhost", host],
                           check=True, stdout=subprocess.PIPE)
        for path in (certificate, key, authority):
            path.chmod(0o600)
        return directory
    # Результаты публикуются только после успешного выпуска всех файлов.
    with tempfile.TemporaryDirectory(prefix=".tls-", dir=directory) as temporary:
        staging = Path(temporary)
        leaf = staging / certificate.name
        private = staging / key.name
        ca = staging / authority.name
        if sys.platform == "darwin":
            if not shutil.which("mkcert"):
                raise RuntimeError("Install mkcert before preparing local TLS: brew install mkcert")
            subprocess.run(["mkcert", "-cert-file", str(leaf), "-key-file", str(private), *HOSTS], check=True)
            root = Path(subprocess.check_output(["mkcert", "-CAROOT"], text=True).strip())
            shutil.copyfile(root / "rootCA.pem", ca)
        else:
            # В CI доверяем только этому CA через cafile, глобальное trust store не меняется.
            root_key = staging / "ca-key.pem"
            subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "30",
                            "-subj", "/CN=Local storefront verification CA", "-addext", "basicConstraints=critical,CA:TRUE",
                            "-keyout", str(root_key), "-out", str(ca)], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            request = staging / "leaf.csr"
            extensions = staging / "leaf.ext"
            extensions.write_text("subjectAltName=" + ",".join("DNS:" + host for host in HOSTS)
                                  + "\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n")
            subprocess.run(["openssl", "req", "-new", "-newkey", "rsa:2048", "-nodes", "-subj", "/CN=" + HOSTS[0],
                            "-keyout", str(private), "-out", str(request)], check=True,
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            subprocess.run(["openssl", "x509", "-req", "-in", str(request), "-CA", str(ca), "-CAkey", str(root_key),
                            "-CAcreateserial", "-days", "30", "-extfile", str(extensions), "-out", str(leaf)],
                           check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        for path in (leaf, private, ca):
            path.chmod(0o600)
            path.replace(directory / path.name)
    return directory


if __name__ == "__main__":
    directory = os.environ.get("STORE_SECRETS_DIR", Path.home() / ".config/java-engineering-course/sprint08")
    if sys.platform == "darwin":
        import importlib.util
        specification = importlib.util.spec_from_file_location("secret_disk", Path(__file__).with_name("secret-disk.py"))
        disk = importlib.util.module_from_spec(specification)
        specification.loader.exec_module(disk)
        directory = disk.unlock(directory, Path(__file__).resolve().parents[3])
    initialize(directory)
    print("Local TLS certificate is ready; existing keys were preserved")
