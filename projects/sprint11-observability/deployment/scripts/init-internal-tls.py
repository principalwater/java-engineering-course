"""Выпуск отдельных сертификатов сервисов; ключ CA остаётся во внешнем хранилище."""

import importlib.util
import os
from pathlib import Path
import secrets
import ssl
import subprocess
import tempfile


SERVICES = ("accounts", "cash", "transfer", "notifications", "front", "gateway", "analytics",
            "authorization", "clickhouse", "logstash", "elasticsearch", "kibana",
            "prometheus", "grafana", "zipkin", "alertmanager", "openbao")
# Локальный стенд требует явного перевыпуска до истечения срока; ключи не ротируются при запуске.
AUTHORITY_DAYS = 365
LEAF_DAYS = 90


def module(name, filename):
    specification = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    loaded = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(loaded)
    return loaded


def run(arguments):
    # OpenSSL/Keytool могут дописать параметры запроса в ошибку; не выводим их необработанными.
    completed = subprocess.run(arguments, capture_output=True, timeout=30)
    if completed.returncode:
        raise RuntimeError(f"Internal TLS phase failed: {Path(arguments[0]).name}")


def existing(files):
    if any(path.exists() or path.is_symlink() for path in files):
        if not all(path.is_file() and not path.is_symlink() and path.stat().st_size for path in files):
            raise ValueError("Incomplete internal TLS material; restore the existing files together")
        for path in files:
            path.chmod(0o600)
        return True
    return False


def authority(directory, openssl):
    certificate, key = (directory / name for name in ("internal_ca.pem", "internal_ca_key.pem"))
    if not existing((certificate, key)):
        with tempfile.TemporaryDirectory(prefix=".internal-ca-", dir=directory) as temporary:
            staging = Path(temporary)
            run([openssl, "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", str(AUTHORITY_DAYS),
                 "-subj", "/CN=Bank internal service CA", "-addext", "basicConstraints=critical,CA:TRUE,pathlen:0",
                 "-addext", "keyUsage=critical,keyCertSign,cRLSign", "-addext", "subjectKeyIdentifier=hash",
                 "-out", str(staging / certificate.name), "-keyout", str(staging / key.name)])
            for target in (certificate, key):
                source = staging / target.name
                source.chmod(0o600)
                source.replace(target)
    run([openssl, "verify", "-x509_strict", "-CAfile", str(certificate), str(certificate)])
    ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER).load_cert_chain(certificate, key)
    return certificate, key


def issue(directory, service, openssl, ca, ca_key):
    names = (service, *(f"{service}.bank11-{environment}.svc.cluster.local"
                        for environment in ("dev", "test", "prod")))
    if service == "openbao":
        names += ("openbao.bank11-system.svc.cluster.local", "openbao-0.openbao-internal.bank11-system.svc.cluster.local")
    certificate = directory / f"internal_{service}_certificate.pem"
    key = directory / f"internal_{service}_key.pem"
    password = directory / f"internal_{service}_keystore_password"
    keystore = directory / f"internal_{service}.p12"
    if not existing((certificate, key, password, keystore)):
        with tempfile.TemporaryDirectory(prefix=f".internal-{service}-", dir=directory) as temporary:
            staging = Path(temporary)
            request, extensions = staging / "leaf.csr", staging / "leaf.ext"
            private, leaf = staging / key.name, staging / certificate.name
            extensions.write_text("subjectAltName=" + ",".join("DNS:" + name for name in names)
                                  + "\nbasicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment"
                                  + "\nextendedKeyUsage=serverAuth,clientAuth\nsubjectKeyIdentifier=hash"
                                  + "\nauthorityKeyIdentifier=keyid,issuer\n")
            run([openssl, "req", "-new", "-newkey", "rsa:2048", "-nodes", "-subj", f"/CN={service}",
                 "-keyout", str(private), "-out", str(request)])
            run([openssl, "x509", "-req", "-in", str(request), "-CA", str(ca), "-CAkey", str(ca_key),
                 "-set_serial", "0x" + secrets.token_hex(16), "-days", str(LEAF_DAYS), "-extfile", str(extensions),
                 "-out", str(leaf)])
            (staging / password.name).write_text(secrets.token_hex(32) + "\n")
            run([openssl, "pkcs12", "-export", "-in", str(leaf), "-inkey", str(private), "-certfile", str(ca),
                 "-out", str(staging / keystore.name), "-passout", "file:" + str(staging / password.name)])
            for target in (certificate, key, password, keystore):
                source = staging / target.name
                source.chmod(0o600)
                source.replace(target)
    for name in names:
        run([openssl, "verify", "-x509_strict", "-purpose", "sslserver", "-CAfile", str(ca),
             "-verify_hostname", name, str(certificate)])
    ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER).load_cert_chain(certificate, key)
    run([openssl, "pkcs12", "-in", str(keystore), "-passin", "file:" + str(password), "-noout"])


def initialize(directory, services=SERVICES):
    directory = Path(directory).expanduser()
    if directory.is_symlink() or directory.resolve().is_relative_to(Path(__file__).resolve().parents[4]):
        raise ValueError("Internal TLS material must remain outside the repository")
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    directory.chmod(0o700)
    openssl = module("bank_local_tls", "init-tls.py").openssl_binary()
    ca, ca_key = authority(directory, openssl)
    for service in services:
        if service not in SERVICES:
            raise ValueError("Unknown internal TLS service identity")
        issue(directory, service, openssl, ca, ca_key)
    return directory


if __name__ == "__main__":
    os.umask(0o077)
    secret_source = module("bank_secret_source", "init-secrets.py")
    initialize(secret_source.secret_directory(Path(__file__).resolve().parents[4]))
    print("Internal service TLS identities are ready; existing keys were preserved")
