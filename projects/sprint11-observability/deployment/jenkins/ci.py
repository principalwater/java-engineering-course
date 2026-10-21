"""Управляет локальным контроллером Jenkins и отдельным агентом банка; постоянное состояние сохраняется.

Порядок: init (необязательно, up вызывает его сам), up, build, stop. Единственный опубликованный сервис —
контроллер на 127.0.0.1 по HTTPS; агент — отдельный контейнер со своим Docker-демоном под TLS.

Источник секретов задаёт оператор через BANK_JENKINS_SECRETS_DIR; значения по умолчанию нет. На macOS это
смонтированный AES-256 диск (тот же secret-disk.py, что у init-secrets.py), в изолированном одноразовом
Linux CI — приватный каталог при BANK_ALLOW_PLAINTEXT_TEST_SECRETS=true. Исключение, которое нельзя убрать:
контроллеру и агенту нужны собственные удостоверения (пароль администратора, ключ и keystore TLS, токен агента,
CA) ещё до появления кластера, поэтому их копии лежат в Docker-томах VM. Это материал входа в CI, а не секреты
приложений и платформы: те живут только в OpenBao.
"""

import argparse
import base64
import contextlib
import datetime
import hashlib
import http.client
import http.cookiejar
import importlib.util
import io
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import ssl
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ElementTree


HERE = Path(__file__).resolve().parent
REPOSITORY = HERE.parents[3]
SECRET_DISK = HERE.parent / "scripts/secret-disk.py"

INSTANCE = "bank11-jenkins"
CONTROLLER = f"{INSTANCE}-controller"
DAEMON = f"{INSTANCE}-docker"
AGENT = f"{INSTANCE}-agent"
VOLUME_HOME = f"{INSTANCE}-home"
VOLUME_DAEMON = f"{INSTANCE}-docker-data"
VOLUME_CERTS = f"{INSTANCE}-docker-certs"
VOLUME_AGENT = f"{INSTANCE}-agent"
VOLUME_CONTROLLER_SECRETS = f"{INSTANCE}-controller-secrets"
VOLUME_AGENT_SECRETS = f"{INSTANCE}-agent-secrets"
VOLUMES = (VOLUME_HOME, VOLUME_DAEMON, VOLUME_CERTS, VOLUME_AGENT, VOLUME_CONTROLLER_SECRETS, VOLUME_AGENT_SECRETS)
OWNER_LABEL = "io.bank11.jenkins.owner"
CONFIG_LABEL = "io.bank11.jenkins.config"
SECRETS_ENVIRONMENT = "BANK_JENKINS_SECRETS_DIR"
PLAINTEXT_ENVIRONMENT = "BANK_ALLOW_PLAINTEXT_TEST_SECRETS"
# Вне 10.251.10.0/24 и 10.251.11.0/24: там runtime банка сам выделяет сети kind внутри демона агента.
DEFAULT_SUBNET = "10.251.20.0/24"

AGENT_NAME = "bank-kubernetes"
JOB_NAME = "bank11-observability"
ADMIN_NAME = "admin"
SNAPSHOT_BRANCH = "jenkins-source"
SOURCE_MOUNT = "/srv/bank11-source.git"
SECRETS_MOUNT = "/run/bank-jenkins"
AGENT_ROOT = "/home/jenkins/agent"
CONTROLLER_INTERNAL_PORT = 8443
DEFAULT_CONTROLLER_PORT = 18443
DAEMON_PORT = 2376
# Docker 28 — последняя линия, с минимальным API которой договаривается клиент Testcontainers проекта;
# digest — мультиархитектурный индекс docker:28-dind (Docker 28.5.2).
DAEMON_IMAGE = "docker:28-dind@sha256:2a232a42256f70d78e3cc5d2b5d6b3276710a0de0596c145f627ecfae90282ac"
JENKINS_UID = 1000
# Контейнер контроллера читает только эти файлы; PEM-ключ остаётся в источнике секретов рядом с keystore.
CONTROLLER_SECRET_FILES = ("admin_password", "tls_keystore.p12", "keystore_password")

IMAGE_BUILD_TIMEOUT = 1800
DAEMON_READY_TIMEOUT = 180
CONTROLLER_READY_TIMEOUT = 420
AGENT_ONLINE_TIMEOUT = 180
QUEUE_TIMEOUT = 600
# Больше 120-минутного таймаута pipeline: зависшую сборку должен объявить Jenkins, а не этот клиент.
BUILD_TIMEOUT = 7800
HTTP_TIMEOUT = 30
CERTIFICATE_RENEWAL_SECONDS = 14 * 24 * 3600
TLS_ADDRESSES = ("127.0.0.1", "::1")
STAGE_LINE = re.compile(r"^\[Pipeline\] \{ \((.+)\)$")
NODE_LINE = re.compile(r"^Running on (\S+) in ", re.MULTILINE)


class Layout:
    """Расположение на хосте, целиком вне репозитория.

    root хранит только открытое состояние (снимок кода и журналы сборок). Всё секретное лежит в secret_source;
    в контейнеры оно попадает через stdin в тома, а не через bind mount.
    """

    def __init__(self, root: Path, secret_source: Path):
        if "," in str(root):
            raise ValueError("The Jenkins home path must not contain a comma")
        self.root = root
        self.secret_source = secret_source
        self.jenkins_secrets = secret_source / "jenkins"
        self.controller_secrets = self.jenkins_secrets / "controller"
        self.agent_secrets = self.jenkins_secrets / "agent"
        self.source = root / "source.git"
        self.logs = root / "logs"


def outside_repository(directory: Path, description: str) -> Path:
    if directory.is_symlink() or directory.resolve().is_relative_to(REPOSITORY.resolve()):
        raise ValueError(f"{description} must be a real directory outside the repository")
    return directory


def layout_from_environment() -> Layout:
    root = outside_repository(Path(os.environ.get("BANK_JENKINS_HOME")
                                   or Path.home() / ".local/share/java-engineering-course/jenkins-bank11"
                                   ).expanduser().absolute(), "The Jenkins home")
    supplied = os.environ.get(SECRETS_ENVIRONMENT)
    # Намеренно без каталога по умолчанию: тихий выбор места привёл бы к открытому хранилищу секретов на Mac.
    if not supplied:
        raise ValueError(f"Set {SECRETS_ENVIRONMENT} to the externally supplied secret directory; "
                         "there is no default location")
    return Layout(root, outside_repository(Path(supplied).expanduser().absolute(), "The Jenkins secret source"))


def load_secret_disk():
    specification = importlib.util.spec_from_file_location("bank_secret_disk", SECRET_DISK)
    disk = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(disk)
    return disk


def require_secret_source(layout: Layout, *, unlock: bool = False) -> None:
    """Допускает зашифрованный диск на macOS и приватный каталог только в изолированном Linux CI.

    Флаг BANK_ALLOW_PLAINTEXT_TEST_SECRETS не делает обычный каталог допустимым на macOS: там его нельзя
    обойти. unlock=True (init, up) создаёт и монтирует диск через общий secret-disk.py, остальные команды
    лишь проверяют, что он уже смонтирован и зашифрован.
    """
    if sys.platform == "darwin":
        disk = load_secret_disk()
        if unlock:
            disk.unlock(layout.secret_source, REPOSITORY)
            return
        directory = layout.secret_source.resolve()
        image = disk.disk_path(directory)
        if not image.is_file() or disk.mounted_image(directory) != image.resolve():
            raise ValueError("The Jenkins secret source must be an unlocked encrypted disk mount; "
                             "run init to create or unlock it")
        disk.require_encryption(image, disk.keychain_password(image))
    elif os.environ.get(PLAINTEXT_ENVIRONMENT) != "true":
        raise ValueError(f"File secrets outside macOS are allowed only in isolated disposable CI; "
                         f"set {PLAINTEXT_ENVIRONMENT}=true there")


def controller_port() -> int:
    return int(os.environ.get("BANK_JENKINS_PORT", DEFAULT_CONTROLLER_PORT))


def private_directory(path: Path) -> None:
    if path.is_symlink():
        raise ValueError("Private directories must not be symlinks")
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    path.chmod(0o700)


def write_private(path: Path, data: bytes) -> None:
    if path.is_symlink():
        raise ValueError("Private files must not be symlinks")
    temporary = path.with_name(f".{path.name}.{secrets.token_hex(4)}.tmp")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())
    temporary.replace(path)


@contextlib.contextmanager
def public_umask():
    # Снимок содержит только закоммиченный код и должен читаться пользователем контейнера при любом uid,
    # на который Docker file sharing отображает файлы.
    previous = os.umask(0o022)
    try:
        yield
    finally:
        os.umask(previous)


def git(arguments: list[str], *, cwd: Path | None = None) -> str:
    environment = dict(os.environ, GIT_TERMINAL_PROMPT="0", GIT_OPTIONAL_LOCKS="0")
    completed = subprocess.run(["git", *arguments], cwd=cwd, env=environment, capture_output=True,
                               text=True, timeout=300)
    if completed.returncode:
        raise RuntimeError(f"git {arguments[0]} failed (exit {completed.returncode}): {completed.stderr.strip()[-300:]}")
    return completed.stdout


def ensure_source_repository(bare: Path) -> None:
    if (bare / "HEAD").is_file():
        return
    with public_umask():
        git(["init", "--bare", "--quiet", "--initial-branch", SNAPSHOT_BRANCH, str(bare)])


def snapshot(repository: Path, bare: Path) -> str:
    """Публикует закоммиченный HEAD с полной историей; pipeline затем собирает именно этот коммит."""
    toplevel = Path(git(["rev-parse", "--show-toplevel"], cwd=repository).strip()).resolve()
    if git(["status", "--porcelain", "--untracked-files=normal"], cwd=toplevel).strip():
        raise ValueError("The working tree has uncommitted or untracked files; commit or remove them, "
                         "because Jenkins builds only committed history")
    head = git(["rev-parse", "--verify", "HEAD^{commit}"], cwd=toplevel).strip()
    ensure_source_repository(bare)
    with public_umask():
        git(["--git-dir", str(bare), "fetch", "--quiet", "--no-tags", "--force", str(toplevel),
             f"+HEAD:refs/heads/{SNAPSHOT_BRANCH}"])
    published = git(["--git-dir", str(bare), "rev-parse", "--verify", f"refs/heads/{SNAPSHOT_BRANCH}^{{commit}}"]).strip()
    if published != head:
        raise RuntimeError("The published snapshot does not match the source HEAD")
    return head


def openssl_binary() -> str:
    for binary in (shutil.which("openssl"), "/opt/homebrew/opt/openssl@3/bin/openssl", "/usr/local/opt/openssl@3/bin/openssl"):
        if binary and Path(binary).is_file() and subprocess.run(
                [binary, "version"], capture_output=True, text=True).stdout.startswith("OpenSSL 3."):
            return binary
    raise RuntimeError("Controller TLS requires OpenSSL 3; on macOS install openssl@3 with Homebrew")


def tls_names() -> tuple[str, ...]:
    return ("localhost", CONTROLLER)


def tls_is_current(openssl: str, layout: Layout) -> bool:
    certificate = layout.controller_secrets / "tls_certificate.pem"
    key = layout.controller_secrets / "tls_private_key.pem"
    keystore = layout.controller_secrets / "tls_keystore.p12"
    authority = layout.agent_secrets / "tls_ca.pem"
    password = layout.controller_secrets / "keystore_password"
    if not all(path.is_file() and not path.is_symlink() and path.stat().st_size
               for path in (certificate, key, authority, keystore, password)):
        return False

    def passes(*arguments: str) -> bool:
        return subprocess.run([openssl, *arguments], capture_output=True).returncode == 0

    chain = ["verify", "-x509_strict", "-CAfile", str(authority)]
    public_key = subprocess.run([openssl, "x509", "-noout", "-pubkey", "-in", str(certificate)], capture_output=True)
    matching_key = subprocess.run([openssl, "pkey", "-pubout", "-in", str(key)], capture_output=True)
    leaf = subprocess.run([openssl, "x509", "-outform", "DER", "-in", str(certificate)], capture_output=True)
    stored = subprocess.run([openssl, "pkcs12", "-in", str(keystore), "-passin", f"file:{password}",
                             "-clcerts", "-nokeys"], capture_output=True)
    stored_leaf = subprocess.run([openssl, "x509", "-outform", "DER"], input=stored.stdout, capture_output=True)
    return (public_key.returncode == 0 and public_key.stdout == matching_key.stdout
            and stored.returncode == 0 and stored_leaf.returncode == 0 and leaf.stdout == stored_leaf.stdout
            and all(passes(*chain, "-verify_hostname", name, str(certificate)) for name in tls_names())
            and all(passes(*chain, "-verify_ip", address, str(certificate)) for address in TLS_ADDRESSES)
            and passes("x509", "-checkend", str(CERTIFICATE_RENEWAL_SECONDS), "-noout", "-in", str(certificate)))


def issue_tls(openssl: str, layout: Layout, authority: Path | None) -> None:
    # Mac использует существующий доверенный CA без изменения trust store. В изолированном Linux CI ключ
    # одноразового CA живёт лишь во временном каталоге внутри источника секретов и не сохраняется.
    with tempfile.TemporaryDirectory(prefix=".tls-", dir=layout.jenkins_secrets) as temporary:
        staging = Path(temporary)

        def run(*arguments: str) -> None:
            subprocess.run([openssl, *arguments], check=True, capture_output=True)

        if authority is not None:
            subprocess.run(["mkcert", "-cert-file", str(staging / "leaf.pem"), "-key-file", str(staging / "key.pem"),
                            *tls_names(), *TLS_ADDRESSES], check=True, capture_output=True)
            shutil.copyfile(authority, staging / "ca.pem")
        else:
            run("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "365", "-subj", "/CN=Bank11 Jenkins local CA",
                "-addext", "basicConstraints=critical,CA:TRUE", "-addext", "keyUsage=critical,keyCertSign,cRLSign",
                "-addext", "subjectKeyIdentifier=hash", "-keyout", str(staging / "ca-key.pem"), "-out", str(staging / "ca.pem"))
            run("req", "-new", "-newkey", "rsa:2048", "-nodes", "-subj", "/CN=localhost",
                "-keyout", str(staging / "key.pem"), "-out", str(staging / "leaf.csr"))
            (staging / "leaf.ext").write_text(
                "subjectAltName=" + ",".join([*(f"DNS:{name}" for name in tls_names()),
                                              *(f"IP:{address}" for address in TLS_ADDRESSES)])
                + "\nbasicConstraints=critical,CA:FALSE\nsubjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid,issuer"
                + "\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n")
            run("x509", "-req", "-in", str(staging / "leaf.csr"), "-CA", str(staging / "ca.pem"),
                "-CAkey", str(staging / "ca-key.pem"), "-CAcreateserial", "-days", "90",
                "-extfile", str(staging / "leaf.ext"), "-out", str(staging / "leaf.pem"))
        # Winstone читает только keystore; его пароль лежит рядом, в том же приватном каталоге.
        password = secrets.token_urlsafe(32) + "\n"
        write_private(staging / "keystore_password", password.encode())
        run("pkcs12", "-export", "-name", "jenkins", "-inkey", str(staging / "key.pem"), "-in", str(staging / "leaf.pem"),
            "-certfile", str(staging / "ca.pem"), "-passout", f"file:{staging / 'keystore_password'}",
            "-out", str(staging / "keystore.p12"))
        ca = (staging / "ca.pem").read_bytes()
        write_private(layout.controller_secrets / "tls_private_key.pem", (staging / "key.pem").read_bytes())
        write_private(layout.controller_secrets / "tls_certificate.pem", (staging / "leaf.pem").read_bytes() + ca)
        write_private(layout.agent_secrets / "tls_ca.pem", ca)
        write_private(layout.controller_secrets / "keystore_password", password.encode())
        write_private(layout.controller_secrets / "tls_keystore.p12", (staging / "keystore.p12").read_bytes())


def initialize(layout: Layout) -> None:
    private_directory(layout.root)
    for directory in (layout.secret_source, layout.jenkins_secrets, layout.controller_secrets,
                      layout.agent_secrets, layout.logs):
        private_directory(directory)
    password = layout.controller_secrets / "admin_password"
    if password.is_symlink():
        raise ValueError("Secret files must not be symlinks")
    if password.exists():
        if not password.is_file() or not re.fullmatch(rb"[A-Za-z0-9_-]{43}\n?", password.read_bytes()):
            raise ValueError("The admin password file must contain a 43-character URL-safe value")
        password.chmod(0o600)
    else:
        write_private(password, (secrets.token_urlsafe(32) + "\n").encode())
    openssl = openssl_binary()
    authority = None
    if sys.platform == "darwin":
        if shutil.which("mkcert") is None:
            raise RuntimeError("Install mkcert before preparing controller TLS")
        root = Path(subprocess.check_output(["mkcert", "-CAROOT"], text=True).strip())
        authority = root / "rootCA.pem"
        if not authority.is_file() or not (root / "rootCA-key.pem").is_file():
            raise ValueError("The existing mkcert CA is unavailable; unlock its encrypted source before continuing")
    materials = [*(layout.controller_secrets / name for name in
                   ("tls_certificate.pem", "tls_private_key.pem", "tls_keystore.p12", "keystore_password")),
                 layout.agent_secrets / "tls_ca.pem"]
    if any(path.exists() or path.is_symlink() for path in materials):
        if (not tls_is_current(openssl, layout)
                or authority is not None and authority.read_bytes() != (layout.agent_secrets / "tls_ca.pem").read_bytes()):
            raise ValueError("Existing controller TLS is incomplete, invalid, foreign or expires within 14 days; "
                             "restore or explicitly replace the certificate, key, CA, keystore and password together")
        for path in (*layout.controller_secrets.iterdir(), *layout.agent_secrets.iterdir()):
            if path.is_symlink() or not path.is_file():
                raise ValueError("Secret files must be regular files, not symlinks")
            path.chmod(0o600)
    else:
        issue_tls(openssl, layout, authority)
    ensure_source_repository(layout.source)


def docker(*arguments: str, timeout: int = 300, check: bool = True,
           data: bytes | None = None) -> subprocess.CompletedProcess:
    completed = subprocess.run(["docker", *arguments], input=data, capture_output=True, timeout=timeout)
    result = subprocess.CompletedProcess(completed.args, completed.returncode,
                                         completed.stdout.decode("utf-8", "replace"),
                                         completed.stderr.decode("utf-8", "replace"))
    if check and result.returncode:
        raise RuntimeError(f"docker {' '.join(arguments[:2])} failed (exit {result.returncode}): "
                           f"{result.stderr.strip()[-500:]}")
    return result


def require_docker() -> None:
    docker("version", "--format", "{{.Server.Version}}", timeout=60)
    endpoint = os.environ.get("DOCKER_HOST") or docker(
        "context", "inspect", "--format", "{{.Endpoints.docker.Host}}", timeout=60).stdout.strip()
    if not endpoint.startswith("unix://"):
        raise ValueError("A local Docker engine is required: the source snapshot is a bind mount")


def inspect(kind: str, name: str) -> dict | None:
    result = docker(kind, "inspect", name, check=False, timeout=60)
    return json.loads(result.stdout)[0] if result.returncode == 0 else None


def labels(info: dict) -> dict:
    return info.get("Labels") or info.get("Config", {}).get("Labels") or {}


def require_owned(info: dict, description: str) -> None:
    if labels(info).get(OWNER_LABEL) != INSTANCE:
        raise ValueError(f"{description} exists without the {INSTANCE} ownership label; refusing adoption")


def ensure_network(subnet: str) -> None:
    existing = inspect("network", INSTANCE)
    if existing:
        require_owned(existing, f"Network {INSTANCE}")
        return
    wanted = ipaddress.ip_network(subnet)
    for identifier in docker("network", "ls", "-q", timeout=60).stdout.split():
        for config in (inspect("network", identifier) or {}).get("IPAM", {}).get("Config") or []:
            other = ipaddress.ip_network(config["Subnet"], strict=False) if config.get("Subnet") else None
            if other and other.version == wanted.version and wanted.overlaps(other):
                raise ValueError(f"Subnet {subnet} overlaps an existing network; set BANK_JENKINS_SUBNET")
    docker("network", "create", "--subnet", subnet, "--label", f"{OWNER_LABEL}={INSTANCE}", INSTANCE, timeout=60)


def ensure_volume(name: str) -> None:
    existing = inspect("volume", name)
    if existing:
        require_owned(existing, f"Volume {name}")
    else:
        docker("volume", "create", "--label", f"{OWNER_LABEL}={INSTANCE}", name, timeout=60)


def context_digest(directory: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(item for item in directory.rglob("*") if item.is_file()):
        digest.update(path.relative_to(directory).as_posix().encode() + b"\0" + path.read_bytes() + b"\0")
    return digest.hexdigest()


def ensure_image(role: str) -> str:
    context = HERE / role
    tag = f"{INSTANCE}-{role}:{context_digest(context)[:16]}"
    if docker("image", "inspect", tag, check=False, timeout=60).returncode:
        docker("build", "--progress=plain", "--tag", tag, "--label", f"{OWNER_LABEL}={INSTANCE}", str(context),
               timeout=IMAGE_BUILD_TIMEOUT)
    return tag


def prepare_agent_volume(image: str) -> None:
    # Нейтральный путь не требует обхода закрытого home пользователя Jenkins от root без DAC_OVERRIDE.
    destination = "/work"
    docker("run", "--rm", "--user", "0:0", "--cap-drop", "ALL", "--cap-add", "CHOWN", "--security-opt",
           "no-new-privileges", "--entrypoint", "chown", "--mount", f"type=volume,src={VOLUME_AGENT},dst={destination}",
           image, f"{JENKINS_UID}:{JENKINS_UID}", destination, timeout=120)


def publish_secrets(volume: str, image: str, files: dict[str, bytes]) -> None:
    """Копирует файлы в том своей роли через stdin; ни аргументы, ни окружение секретов не содержат.

    Это единственная точка, где удостоверения CI покидают источник секретов: том лежит в VM Docker и
    удаляется вместе с ней, но без копии контейнер не получил бы ни свой ключ, ни токен агента.
    """
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w") as archive:
        for name, data in sorted(files.items()):
            member = tarfile.TarInfo(name)
            member.size, member.mode, member.uid, member.gid, member.mtime = len(data), 0o400, JENKINS_UID, JENKINS_UID, int(time.time())
            archive.addfile(member, io.BytesIO(data))
    # Распаковка отдаёт каждый файл uid Jenkins и затем от root выставляет режим: нужны CHOWN и FOWNER.
    docker("run", "--rm", "-i", "--user", "0:0", "--cap-drop", "ALL", "--cap-add", "CHOWN", "--cap-add", "FOWNER",
           "--cap-add", "DAC_OVERRIDE", "--security-opt", "no-new-privileges", "--entrypoint", "tar",
           "--mount", f"type=volume,src={volume},dst=/secrets", image,
           "-xf", "-", "-C", "/secrets", data=buffer.getvalue(), timeout=120)


def daemon_arguments() -> list[str]:
    # Единственный привилегированный контейнер. В нём живёт собственный Docker-демон агента, поэтому сокет хоста
    # никому не отдаётся.
    return ["run", "--detach", "--name", DAEMON, "--network", INSTANCE, "--privileged",
            "--env", "DOCKER_TLS_CERTDIR=/certs",
            "--mount", f"type=volume,src={VOLUME_CERTS},dst=/certs/client",
            "--mount", f"type=volume,src={VOLUME_DAEMON},dst=/var/lib/docker",
            # kind разрешает источники bind mount на стороне демона, поэтому демон видит рабочий том по тому же пути.
            "--mount", f"type=volume,src={VOLUME_AGENT},dst={AGENT_ROOT}",
            DAEMON_IMAGE, "--storage-driver=overlay2"]


def controller_arguments(layout: Layout, image: str, port: int) -> list[str]:
    environment = {
        # Git plugin запрещает file:// по умолчанию; здесь доступен только явный read-only снимок доверенного HEAD.
        "JAVA_OPTS": "-Xmx1g -Djenkins.install.runSetupWizard=false "
                     "-Dhudson.plugins.git.GitSCM.ALLOW_LOCAL_CHECKOUT=true",
        "BANK_JENKINS_HTTPS_PORT": str(CONTROLLER_INTERNAL_PORT),
        "BANK_JENKINS_ADMIN": ADMIN_NAME,
        "BANK_JENKINS_AGENT_NAME": AGENT_NAME,
        "BANK_JENKINS_AGENT_ROOT": AGENT_ROOT,
        "BANK_JENKINS_ROOT_URL": f"https://127.0.0.1:{port}/",
        "BANK_JENKINS_JOB": JOB_NAME,
        "BANK_JENKINS_SCM_URL": f"file://{SOURCE_MOUNT}",
        "BANK_JENKINS_SCM_BRANCH": f"refs/heads/{SNAPSHOT_BRANCH}",
        "BANK_JENKINS_SCRIPT_PATH": (HERE.parents[1] / "Jenkinsfile").relative_to(REPOSITORY).as_posix(),
    }
    arguments = ["run", "--detach", "--name", CONTROLLER, "--network", INSTANCE,
                 "--publish", f"127.0.0.1:{port}:{CONTROLLER_INTERNAL_PORT}",
                 "--cap-drop", "ALL", "--security-opt", "no-new-privileges"]
    for name, value in environment.items():
        arguments += ["--env", f"{name}={value}"]
    return arguments + [
        "--mount", f"type=volume,src={VOLUME_HOME},dst=/var/jenkins_home",
        "--mount", f"type=volume,src={VOLUME_CONTROLLER_SECRETS},dst={SECRETS_MOUNT},readonly",
        "--mount", f"type=bind,src={layout.source},dst={SOURCE_MOUNT},readonly", image]


def agent_arguments(layout: Layout, image: str) -> list[str]:
    # Общий сетевой namespace с демоном оставляет API Kubernetes и опубликованный порт банка на 127.0.0.1,
    # как того ждёт runtime банка, и ничего не публикует на хосте. Поэтому же 127.0.0.1 агента — это
    # loopback демона: проброшенные порты kind, port-forward и имена *.localhost попадают в тот же namespace.
    return ["run", "--detach", "--name", AGENT, "--init", "--network", f"container:{DAEMON}",
            "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
            "--env", f"DOCKER_HOST=tcp://127.0.0.1:{DAEMON_PORT}", "--env", "DOCKER_TLS_VERIFY=1",
            "--env", "DOCKER_CERT_PATH=/certs/client",
            "--mount", f"type=volume,src={VOLUME_CERTS},dst=/certs/client,readonly",
            "--mount", f"type=volume,src={VOLUME_AGENT},dst={AGENT_ROOT}",
            "--mount", f"type=volume,src={VOLUME_AGENT_SECRETS},dst={SECRETS_MOUNT},readonly",
            "--mount", f"type=bind,src={layout.source},dst={SOURCE_MOUNT},readonly",
            image, "-url", f"https://{CONTROLLER}:{CONTROLLER_INTERNAL_PORT}/", "-name", AGENT_NAME,
            "-secret", f"@{SECRETS_MOUNT}/agent_secret", "-webSocket", "-workDir", AGENT_ROOT]


def started_at(info: dict) -> float:
    match = re.fullmatch(r"(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d+))?Z", info["State"]["StartedAt"])
    if not match:
        return 0.0
    fraction = (match.group(2) or "0").ljust(6, "0")[:6]
    return datetime.datetime.fromisoformat(f"{match.group(1)}.{fraction}+00:00").timestamp()


def converge(name: str, arguments: list[str], inputs: tuple | list = ()) -> None:
    """Запускает свой контейнер; пересоздаёт его только при смене конфигурации, перезапускает — если вход новее."""
    digest = hashlib.sha256(json.dumps(arguments).encode()).hexdigest()[:16]
    existing = inspect("container", name)
    if existing:
        require_owned(existing, f"Container {name}")
        if labels(existing).get(CONFIG_LABEL) == digest:
            if not existing["State"]["Running"]:
                docker("start", name, timeout=120)
            elif inputs and started_at(existing) < max(inputs):
                docker("restart", "--time", "60", name, timeout=180)
            return
        if existing["State"]["Running"]:
            docker("stop", "--time", "60", name, timeout=180)
        docker("rm", "--volumes", name, timeout=60)
    docker(*arguments[:2], "--label", f"{OWNER_LABEL}={INSTANCE}", "--label", f"{CONFIG_LABEL}={digest}",
           *arguments[2:], timeout=120)


def wait_until(description: str, probe, timeout: int, *, failure=None, interval: float = 2.0) -> None:
    deadline = time.monotonic() + timeout
    while not probe():
        message = failure() if failure else None
        if message:
            raise RuntimeError(message)
        if time.monotonic() >= deadline:
            raise RuntimeError(f"Timed out after {timeout} s waiting for {description}")
        time.sleep(interval)


def container_failure(name: str) -> str | None:
    info = inspect("container", name)
    if info and info["State"]["Running"]:
        return None
    logs = docker("logs", "--tail", "20", name, check=False, timeout=60)
    return f"Container {name} is not running:\n{(logs.stdout + logs.stderr).strip()}"


def daemon_ready() -> bool:
    return (docker("exec", DAEMON, "docker", "info", "--format", "{{.ServerVersion}}", check=False, timeout=60).returncode == 0
            and docker("exec", DAEMON, "test", "-s", "/certs/client/key.pem", check=False, timeout=60).returncode == 0)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *arguments, **keywords):
        return None


class Controller:
    """Клиент с аутентификацией для loopback HTTPS; учётные данные не покидают 127.0.0.1."""

    def __init__(self, layout: Layout, port: int):
        authority = layout.agent_secrets / "tls_ca.pem"
        password = (layout.controller_secrets / "admin_password").read_text(encoding="ascii").strip()
        self._authorization = "Basic " + base64.b64encode(f"{ADMIN_NAME}:{password}".encode()).decode("ascii")
        self._opener = urllib.request.build_opener(
            NoRedirect, urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=str(authority))),
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self._base = f"https://127.0.0.1:{port}"

    def request(self, method: str, path: str, *, authenticated: bool = True, data: bytes | None = None,
                headers: dict | None = None) -> tuple[int, http.client.HTTPMessage, bytes]:
        request = urllib.request.Request(self._base + path, data=data, method=method, headers=headers or {})
        if authenticated:
            request.add_header("Authorization", self._authorization)
        try:
            with self._opener.open(request, timeout=HTTP_TIMEOUT) as response:
                return response.status, response.headers, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.headers, error.read()

    def json(self, path: str) -> dict:
        status, _, body = self.request("GET", path)
        if status != 200:
            raise RuntimeError(f"Controller request {path.split('?')[0]} failed (HTTP {status})")
        return json.loads(body)

    def ready(self) -> bool:
        try:
            return self.request("GET", "/login", authenticated=False)[0] == 200
        except ssl.SSLCertVerificationError as error:
            raise RuntimeError("The controller certificate is not trusted by the agent CA file; "
                               "run init to reissue the TLS material") from error
        except (OSError, http.client.HTTPException):
            return False

    def verify_security(self) -> None:
        if self.request("GET", "/api/json", authenticated=False)[0] not in (401, 403):
            raise RuntimeError("The controller allows anonymous access; stop it and inspect the configuration")
        expected = {"numExecutors": 0, "useSecurity": True, "useCrumbs": True, "slaveAgentPort": -1}
        details = self.json("/api/json?tree=" + ",".join(expected))
        if {key: details.get(key) for key in expected} != expected:
            raise RuntimeError("Unexpected controller security posture: "
                               + json.dumps({key: details.get(key) for key in expected}))

    def agent_secret(self) -> str:
        status, _, body = self.request("GET", f"/computer/{AGENT_NAME}/jenkins-agent.jnlp")
        if status != 200:
            raise RuntimeError(f"The agent node is not defined on the controller (HTTP {status})")
        arguments = [element.text for element in ElementTree.fromstring(body).iter("argument")]
        if len(arguments) < 2 or arguments[1] != AGENT_NAME or not re.fullmatch(r"[0-9a-f]{64}", arguments[0] or ""):
            raise RuntimeError("Unexpected agent connection descriptor")
        return arguments[0]

    def agent_online(self) -> bool:
        try:
            return self.json(f"/computer/{AGENT_NAME}/api/json?tree=offline")["offline"] is False
        except (RuntimeError, OSError, http.client.HTTPException):
            return False

    def busy(self) -> bool:
        return self.json("/computer/api/json?tree=busyExecutors")["busyExecutors"] > 0

    def queue(self) -> int:
        crumb = self.json("/crumbIssuer/api/json")
        status, headers, _ = self.request("POST", f"/job/{JOB_NAME}/build", data=b"",
                                          headers={crumb["crumbRequestField"]: crumb["crumb"]})
        match = re.search(r"/queue/item/(\d+)/?$", headers.get("Location", ""))
        if status != 201 or not match:
            raise RuntimeError(f"The build was not queued (HTTP {status})")
        return int(match.group(1))

    def started_build(self, item: int) -> int:
        deadline = time.monotonic() + QUEUE_TIMEOUT
        queued = {}
        while time.monotonic() < deadline:
            queued = self.json(f"/queue/item/{item}/api/json")
            if queued.get("cancelled"):
                raise RuntimeError("The queued build was cancelled")
            if queued.get("executable"):
                return queued["executable"]["number"]
            time.sleep(2)
        raise RuntimeError(f"The build did not start within {QUEUE_TIMEOUT} s: {queued.get('why')}")

    def follow(self, number: int, log: Path, deadline: float) -> None:
        """Пишет консоль в приватный файл и печатает только названия стадий."""
        offset = 0
        descriptor = os.open(log, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, "wb") as stream:
            while time.monotonic() < deadline:
                status, headers, body = self.request("GET", f"/job/{JOB_NAME}/{number}/logText/progressiveText?start={offset}")
                if status != 200:
                    raise RuntimeError(f"Cannot read the console of build {number} (HTTP {status})")
                stream.write(body)
                stream.flush()
                for line in body.decode("utf-8", "replace").splitlines():
                    stage = STAGE_LINE.match(line)
                    if stage:
                        print(f"stage: {stage.group(1)}", flush=True)
                offset = int(headers.get("X-Text-Size", offset))
                if headers.get("X-More-Data") != "true":
                    return
                time.sleep(3)
        raise RuntimeError(f"Build {number} did not finish within the client timeout; it keeps running on the controller")


def connect(layout: Layout) -> Controller | None:
    initialized = (layout.agent_secrets / "tls_ca.pem").is_file() and (layout.controller_secrets / "admin_password").is_file()
    return Controller(layout, controller_port()) if initialized else None


def agent_files(layout: Layout) -> dict[str, bytes]:
    return {name: (layout.agent_secrets / name).read_bytes() for name in ("tls_ca.pem", "agent_secret")}


def command_init(args: argparse.Namespace) -> None:
    layout = layout_from_environment()
    require_secret_source(layout, unlock=True)
    initialize(layout)
    print(f"Controller secrets and TLS are ready in {layout.jenkins_secrets}; existing values were preserved")


def command_snapshot(args: argparse.Namespace) -> None:
    layout = layout_from_environment()
    print(f"Published snapshot {snapshot(REPOSITORY, layout.source)} as {SNAPSHOT_BRANCH}")


def command_up(args: argparse.Namespace) -> None:
    layout = layout_from_environment()
    require_docker()
    require_secret_source(layout, unlock=True)
    initialize(layout)
    port = controller_port()
    controller_image, agent_image = ensure_image("controller"), ensure_image("agent")
    ensure_network(os.environ.get("BANK_JENKINS_SUBNET", DEFAULT_SUBNET))
    for volume in VOLUMES:
        ensure_volume(volume)
    prepare_agent_volume(agent_image)
    publish_secrets(VOLUME_CONTROLLER_SECRETS, controller_image, {
        name: (layout.controller_secrets / name).read_bytes()
        for name in CONTROLLER_SECRET_FILES})
    converge(DAEMON, daemon_arguments())
    wait_until("the dedicated Docker daemon", daemon_ready, DAEMON_READY_TIMEOUT, failure=lambda: container_failure(DAEMON))
    secret_times = [path.stat().st_mtime for path in layout.controller_secrets.iterdir()]
    converge(CONTROLLER, controller_arguments(layout, controller_image, port), secret_times)
    controller = Controller(layout, port)
    wait_until("the controller login page", controller.ready, CONTROLLER_READY_TIMEOUT,
               failure=lambda: container_failure(CONTROLLER))
    controller.verify_security()
    secret_file = layout.agent_secrets / "agent_secret"
    secret = controller.agent_secret().encode("ascii")
    if not secret_file.is_file() or secret_file.read_bytes() != secret:
        write_private(secret_file, secret)
    publish_secrets(VOLUME_AGENT_SECRETS, agent_image, agent_files(layout))
    agent_inputs = [started_at(inspect("container", DAEMON)), *(path.stat().st_mtime for path in layout.agent_secrets.iterdir())]
    converge(AGENT, agent_arguments(layout, agent_image), agent_inputs)
    wait_until("the agent connection", controller.agent_online, AGENT_ONLINE_TIMEOUT, failure=lambda: container_failure(AGENT))
    print(f"Controller https://127.0.0.1:{port}/ (user {ADMIN_NAME}, password file "
          f"{layout.controller_secrets / 'admin_password'}) with 0 executors")
    print(f"Agent {AGENT} is online with its own Docker daemon; job {JOB_NAME}")


def command_build(args: argparse.Namespace) -> None:
    layout = layout_from_environment()
    require_secret_source(layout)
    head = snapshot(REPOSITORY, layout.source)
    controller = Controller(layout, controller_port())
    if not controller.ready() or not controller.agent_online():
        raise RuntimeError("The controller or the agent is not online; run up first")
    deadline = time.monotonic() + args.timeout
    number = controller.started_build(controller.queue())
    log = layout.logs / f"build-{number}.log"
    print(f"Build {number} runs commit {head}; console is saved to {log}", flush=True)
    controller.follow(number, log, deadline)
    while True:
        details = controller.json(f"/job/{JOB_NAME}/{number}/api/json?tree=building,result,actions[lastBuiltRevision[SHA1]]")
        if not details["building"]:
            break
        if time.monotonic() >= deadline:
            raise RuntimeError(f"Build {number} did not finish within the client timeout")
        time.sleep(3)
    if details["result"] != "SUCCESS":
        raise RuntimeError(f"Build {number} finished with {details['result']}; the full console is in {log}")
    console = log.read_text(encoding="utf-8", errors="replace")
    nodes = set(NODE_LINE.findall(console))
    revisions = {action["lastBuiltRevision"]["SHA1"] for action in details["actions"] if action.get("lastBuiltRevision")}
    executors = {item["displayName"]: item["numExecutors"] for item in
                 controller.json("/computer/api/json?tree=computer[displayName,numExecutors]")["computer"]}
    if nodes != {AGENT_NAME}:
        raise RuntimeError(f"Build {number} ran on {sorted(nodes) or 'no node'} instead of only {AGENT_NAME}")
    if revisions != {head}:
        raise RuntimeError(f"Build {number} used revisions {sorted(revisions)} instead of the snapshot {head}")
    if any(count for name, count in executors.items() if name != AGENT_NAME):
        raise RuntimeError("The controller has executors; builds must stay on the dedicated agent")
    print(f"Build {number} succeeded only on {AGENT_NAME} at {head}; controller executors 0")


def command_status(args: argparse.Namespace) -> None:
    layout = layout_from_environment()
    require_docker()
    for name in (DAEMON, CONTROLLER, AGENT):
        info = inspect("container", name)
        print(f"{name}: {'absent' if info is None else info['State']['Status']}")
    if (layout.source / "HEAD").is_file():
        published = git(["--git-dir", str(layout.source), "for-each-ref", "--format=%(objectname)",
                         f"refs/heads/{SNAPSHOT_BRANCH}"]).strip()
        print(f"snapshot: {published or 'none'}")
    controller = None
    try:
        require_secret_source(layout)
        controller = connect(layout)
    except ValueError as error:
        print(f"secret source: unavailable ({error})")
    ready = controller is not None and controller.ready()
    print(f"controller ready: {ready}; agent online: {ready and controller.agent_online()}")


def command_stop(args: argparse.Namespace) -> None:
    layout = layout_from_environment()
    require_docker()
    if not args.force:
        # Закрытый диск скрыл бы работающую сборку: без пароля администратора контроллер не спросить.
        require_secret_source(layout)
        controller = connect(layout)
        if controller is not None and controller.ready() and controller.busy():
            raise ValueError("A build is running; wait for it to finish or pass --force")
    # Агент делит сетевой namespace с демоном и потому останавливается первым; контроллер обслуживает до конца.
    for name, grace in ((AGENT, 60), (DAEMON, 180), (CONTROLLER, 60)):
        info = inspect("container", name)
        if info is None:
            continue
        require_owned(info, f"Container {name}")
        if info["State"]["Running"]:
            docker("stop", "--time", str(grace), name, timeout=grace + 60)
    print("Stopped the owned agent, Docker daemon and controller; volumes, network, images, secrets and snapshot are preserved")


def main() -> None:
    os.umask(0o077)
    parser = argparse.ArgumentParser(description="Run the isolated bank Jenkins controller and its dedicated TLS Docker agent")
    subparsers = parser.add_subparsers(dest="operation", required=True)
    subparsers.add_parser("init", help="create the admin password and TLS material in the external secret source")
    subparsers.add_parser("up", help="build the images and start or resume the owned containers")
    subparsers.add_parser("snapshot", help="publish the committed HEAD with its history for Jenkins")
    build = subparsers.add_parser("build", help="snapshot, run the pipeline and verify it ran on the agent")
    build.add_argument("--timeout", type=int, default=BUILD_TIMEOUT)
    subparsers.add_parser("status", help="show the owned containers and the controller state")
    stop = subparsers.add_parser("stop", help="stop only the owned containers and keep all state")
    stop.add_argument("--force", action="store_true")
    args = parser.parse_args()
    {"init": command_init, "up": command_up, "snapshot": command_snapshot, "build": command_build,
     "status": command_status, "stop": command_stop}[args.operation](args)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError, http.client.HTTPException) as error:
        print(f"FAIL {error}", file=sys.stderr)
        sys.exit(1)
    except KeyboardInterrupt:
        sys.exit(130)
