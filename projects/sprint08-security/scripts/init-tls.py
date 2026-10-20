"""Локальный TLS: приватный ключ остаётся рядом с Docker secrets, CA не попадает в контейнер."""

import os
from pathlib import Path
import shutil
import ssl
import subprocess
import sys
import tempfile


HOSTS = ("shop.valgrind-home.localhost", "auth.valgrind-home.localhost")


def openssl_binary():
    binary = shutil.which("openssl")
    if binary and subprocess.check_output([binary, "version"], text=True).startswith("OpenSSL 3."):
        return binary
    if sys.platform == "darwin" and shutil.which("brew"):
        prefix = subprocess.check_output(["brew", "--prefix", "openssl@3"], text=True).strip()
        candidate = Path(prefix) / "bin/openssl"
        if candidate.is_file():
            return str(candidate)
    raise RuntimeError("Local TLS requires OpenSSL 3; on macOS install openssl@3 with Homebrew")


def protect_mkcert_key(directory):
    root = Path(subprocess.check_output(["mkcert", "-CAROOT"], text=True).strip())
    source = root / "rootCA-key.pem"
    if not source.exists():
        return
    target_directory = Path(directory) / "local-ca"
    target_directory.mkdir(exist_ok=True, mode=0o700)
    target = target_directory / "rootCA-key.pem"
    if source.is_symlink():
        if source.resolve() != target.resolve():
            raise ValueError("mkcert CA key already belongs to another encrypted store")
        return
    if target.exists() and target.read_bytes() != source.read_bytes():
        raise ValueError("Encrypted CA key differs from the current mkcert key")
    if not target.exists():
        shutil.copyfile(source, target)
    target.chmod(0o400)
    with target.open("rb") as key_file:
        os.fsync(key_file.fileno())
    if target.read_bytes() != source.read_bytes():
        raise RuntimeError("CA key migration verification failed")
    # Сертификат CA и доверие macOS сохраняются: меняется только хранение его приватного ключа.
    temporary = root / ".rootCA-key-encrypted"
    if temporary.exists() or temporary.is_symlink():
        raise ValueError("Recover the pending mkcert key link before continuing")
    temporary.symlink_to(target)
    temporary.replace(source)


def initialize(directory):
    directory = Path(directory).expanduser()
    if directory.is_symlink() or directory.resolve().is_relative_to(Path(__file__).resolve().parents[3]):
        raise ValueError("TLS material must remain outside the repository")
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    directory.chmod(0o700)
    openssl = openssl_binary()
    certificate = directory / "tls_certificate.pem"
    key = directory / "tls_private_key.pem"
    authority = directory / "tls_ca.pem"
    for path in (certificate, key, authority):
        if path.is_symlink():
            raise ValueError("TLS files must not be symlinks")
    if any(path.exists() for path in (certificate, key, authority)):
        if not all(path.is_file() and path.stat().st_size for path in (certificate, key, authority)):
            raise ValueError("TLS files are incomplete; restore the certificate, key and CA together")
        for host in HOSTS:
            # x509 -checkhost в OpenSSL 3.0 возвращает успех даже при несовпадении имени.
            subprocess.run([openssl, "verify", "-x509_strict", "-CAfile", str(authority), "-verify_hostname", host, str(certificate)],
                           check=True, stdout=subprocess.PIPE)
        ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER).load_cert_chain(certificate, key)
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
            # Python 3.13+ проверяет назначение ключа CA по строгому профилю X.509.
            subprocess.run([openssl, "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "30",
                            "-subj", "/CN=Local storefront verification CA", "-addext", "basicConstraints=critical,CA:TRUE",
                            "-addext", "keyUsage=critical,keyCertSign,cRLSign",
                            "-addext", "subjectKeyIdentifier=hash",
                            "-keyout", str(root_key), "-out", str(ca)], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            request = staging / "leaf.csr"
            extensions = staging / "leaf.ext"
            extensions.write_text("subjectAltName=" + ",".join("DNS:" + host for host in HOSTS)
                                  + "\nbasicConstraints=critical,CA:FALSE\nsubjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid,issuer"
                                  + "\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n")
            subprocess.run([openssl, "req", "-new", "-newkey", "rsa:2048", "-nodes", "-subj", "/CN=" + HOSTS[0],
                            "-keyout", str(private), "-out", str(request)], check=True,
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            subprocess.run([openssl, "x509", "-req", "-in", str(request), "-CA", str(ca), "-CAkey", str(root_key),
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
        # Единственный CA нужен всем локальным профилям; его ключ не копируется между стендами.
        ca_directory = disk.unlock(Path.home() / ".config/java-engineering-course/sprint08", Path(__file__).resolve().parents[3])
        if shutil.which("mkcert"):
            protect_mkcert_key(ca_directory)
    initialize(directory)
    if sys.platform == "darwin":
        protect_mkcert_key(ca_directory)
    print("Local TLS certificate is ready; existing keys were preserved")
