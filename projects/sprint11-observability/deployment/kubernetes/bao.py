"""Инициализация и проверка своего OpenBao; значения секретов не попадают в argv, URL, логи или Terraform."""

import argparse
import base64
import contextlib
import http.client
import json
import os
from pathlib import Path
import re
import select
import socket
import ssl
import subprocess
import sys
import threading
import time
import uuid

import yaml

from prepare import APPS, project_dir
from runtime import OWNER_LABEL, kubectl, owned_identity, require_private_source


OPENBAO_NAMESPACE = "bank11-system"
POD = "openbao-0"
# Сертификат OpenBao должен содержать это имя; loopback-адрес port-forward в него не входит.
SERVER_NAME = f"openbao.{OPENBAO_NAMESPACE}.svc.cluster.local"
API_PORT = 8200
TLS_SECRET = "openbao-tls"
# Ключи TLS Secret, смонтированные чартом, соответствуют внешним файлам из init-internal-tls.py.
TLS_FILES = {"ca.crt": "internal_ca.pem", "tls.crt": "internal_openbao_certificate.pem",
             "tls.key": "internal_openbao_key.pem"}
KV_MOUNT = "kv"
AUTH_MOUNT = "kubernetes"
KUBERNETES_HOST = "https://kubernetes.default.svc:443"
AUDIENCE = "openbao"
AUDIT_FILE = "/openbao/audit/audit.log"
# Эту service account использует External Secrets Operator для всех привязок вне Java.
ESO_ACCOUNT = "eso"
# Короткоживущие продлеваемые токены: после предельного срока клиент входит заново.
TOKEN_TTL = 900
TOKEN_MAX_TTL = 3600
VERIFY_LABEL = "bank11.io/openbao-verify"
POD_TIMEOUT = 300
REQUEST_TIMEOUT = 30
MAX_RESPONSE_BYTES = 16 * 1024 ** 2
MAX_SNAPSHOT_BYTES = 256 * 1024 ** 2
MAX_SECRET_BYTES = 64 * 1024
# Отрицательная проба принимает только 403: так v2.7.1 отклоняет неверные имя service account, namespace,
# audience или подпись (internal/builtin/credential/kubernetes/path_login.go), а также запрет ACL.
# Другой статус означает сбой самой пробы: например, 503 при seal или 500 при неработающем reviewer.
DENIED = (403,)
FAILURES = {400: "request rejected", 403: "permission denied", 404: "not found", 429: "standby node",
            500: "internal error", 501: "not initialized", 503: "sealed or unavailable"}


class BaoError(RuntimeError):
    """Категория сбоя API: тело ответа может отразить секреты, поэтому сохраняется только категория."""

    def __init__(self, method: str, path: str, category: str, status: int | None = None):
        super().__init__(f"OpenBao {method} {path} failed: {category}" + (f" (HTTP {status})" if status else ""))
        self.status = status
        self.transient = status in (429, 503) or category == "unreachable"


class Forwarded(http.client.HTTPSConnection):
    """Соединение с loopback port-forward с проверкой сертификата по настоящему имени сервиса."""

    def __init__(self, port: int, context: ssl.SSLContext):
        super().__init__(SERVER_NAME, API_PORT, timeout=REQUEST_TIMEOUT, context=context)
        self.forward_port = port
        self.tls_context = context

    def connect(self):
        raw = socket.create_connection(("127.0.0.1", self.forward_port), self.timeout)
        try:
            self.sock = self.tls_context.wrap_socket(raw, server_hostname=SERVER_NAME)
        except BaseException:
            raw.close()
            raise


class Bao:
    """HTTP API OpenBao через проверенный TLS; доверие ограничено CA начальной настройки."""

    def __init__(self, port: int, ca_file: Path):
        self.port = port
        self.context = ssl.create_default_context(cafile=str(ca_file))
        self.context.verify_flags |= ssl.VERIFY_X509_STRICT
        self.context.minimum_version = ssl.TLSVersion.TLSv1_2

    def request(self, method: str, path: str, *, token: str | None = None, body: dict | None = None,
                content_type: str = "application/json", sink=None):
        headers = {"Accept": "application/json"}
        if token:
            headers["X-Vault-Token"] = token
        payload = None
        if body is not None:
            payload, headers["Content-Type"] = json.dumps(body).encode(), content_type
        connection = Forwarded(self.port, self.context)
        try:
            connection.request(method, f"/v1/{path}", payload, headers)
            response = connection.getresponse()
            if response.status >= 300:
                raise BaoError(method, path, FAILURES.get(response.status, "error"), response.status)
            if sink is not None:
                received = 0
                while chunk := response.read(1024 ** 2):
                    received += len(chunk)
                    if received > MAX_SNAPSHOT_BYTES:
                        raise BaoError(method, path, "response too large")
                    sink.write(chunk)
                return None
            data = response.read(MAX_RESPONSE_BYTES + 1)
        except ssl.SSLCertVerificationError:
            raise BaoError(method, path, "TLS verification failed") from None
        except ssl.SSLError:
            raise BaoError(method, path, "TLS error") from None
        except (OSError, http.client.HTTPException):
            raise BaoError(method, path, "unreachable") from None
        finally:
            connection.close()
        if len(data) > MAX_RESPONSE_BYTES:
            raise BaoError(method, path, "response too large")
        try:
            return json.loads(data) if data else None
        except ValueError:
            raise BaoError(method, path, "malformed response") from None

    def find(self, path: str, token: str):
        try:
            return self.request("GET", path, token=token)
        except BaoError as error:
            if error.status == 404:
                return None
            raise

    def denied(self, method: str, path: str, **arguments) -> bool:
        try:
            self.request(method, path, **arguments)
        except BaoError as error:
            if error.status in DENIED:
                return True
            raise
        return False


def unwrap(response: dict | None) -> dict:
    # Системные endpoints возвращают data и для совместимости дублируют ключи на верхнем уровне.
    response = response or {}
    return response.get("data") or response


def eventually(check, message: str, timeout: int = 60) -> None:
    deadline = time.monotonic() + timeout
    while True:
        try:
            if check():
                return
        except BaoError as error:
            if not error.transient:
                raise
        if time.monotonic() >= deadline:
            raise RuntimeError(message)
        time.sleep(2)


def private_source(directory: Path, name: str) -> Path:
    path = directory / name
    if not path.is_file() or path.is_symlink() or path.stat().st_mode & 0o077:
        raise ValueError(f"Secret source is absent or not private: {name}")
    return path


def secret_text(directory: Path, name: str) -> str:
    data = private_source(directory, name).read_bytes()
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        raise ValueError(f"Secret source is not UTF-8 text: {name}") from None
    if not text.strip() or len(data) > MAX_SECRET_BYTES:
        raise ValueError(f"Secret source is empty or too large: {name}")
    # У однострочного значения убирается завершающий перевод строки, недопустимый в свойстве Spring;
    # многострочные значения, например PEM, сохраняются побайтово.
    line = text.rstrip("\n")
    return line if "\n" not in line else text


def recovery_path(secret_dir: Path, cluster: str) -> Path:
    return secret_dir / "openbao" / cluster / "recovery.json"


def load_recovery(path: Path, cluster: str) -> dict:
    if not path.is_file() or path.is_symlink() or path.stat().st_mode & 0o077:
        raise ValueError("OpenBao recovery file is absent or not private")
    document = json.loads(path.read_text(encoding="utf-8"))
    if (document.get("version") != 1 or document.get("cluster") != cluster
            or not all(isinstance(document.get(key), str) and document[key] for key in ("unseal_key", "root_token"))):
        raise ValueError("OpenBao recovery file does not match this cluster")
    return document


def expected_image() -> str:
    image = yaml.safe_load((project_dir() / "deployment/openbao/values.yaml").read_text(encoding="utf-8"))["server"]["image"]
    return f"{image['registry']}/{image['repository']}:{image['tag']}"


def policy_text(paths, *, inspect_mount: bool = False) -> str:
    # Каждая секция даёт только read одного точного пути: без wildcard, metadata и list.
    metadata = f'path "sys/mounts/{KV_MOUNT}" {{\n  capabilities = ["read"]\n}}\n' if inspect_mount else ""
    return metadata + "".join(f'path "{KV_MOUNT}/data/{path}" {{\n  capabilities = ["read"]\n}}\n' for path in sorted(paths))


def role_payload(namespace: str, role: str, account: str) -> dict:
    return {"bound_service_account_names": [account], "bound_service_account_namespaces": [namespace],
            "audience": AUDIENCE, "token_policies": [role], "token_ttl": TOKEN_TTL, "token_max_ttl": TOKEN_MAX_TTL,
            "token_type": "service", "token_num_uses": 0}


def plan(namespace: str, bindings: dict) -> dict:
    """Связать непустую привязку с KV-путём <namespace>/<name> и единственной читающей его учётной записью.

    Java-сервис читает только свой путь через свою service account (Spring Cloud Vault); остальные привязки
    синхронизирует общая учётная запись External Secrets. Привязки без секретов не получают учётную запись.
    """
    roles = {}
    for binding, files in sorted(bindings.items()):
        if not re.fullmatch(r"bank11-[a-z][a-z0-9-]*", binding):
            raise ValueError("Unexpected Secret binding name")
        if any(not re.fullmatch(r"[a-zA-Z0-9_.-]+", name) for item in files.items() for name in item):
            raise ValueError("Secret projection filenames must not contain paths")
        if not files:
            continue
        name = binding.removeprefix("bank11-")
        account = name if name in APPS else ESO_ACCOUNT
        entry = roles.setdefault(f"{namespace}-{account}", {"account": account, "paths": {}})
        entry["paths"][f"{namespace}/{name}"] = files
    return roles


def read_bindings(state_dir: Path, namespace: str) -> dict:
    return plan(namespace, json.loads((state_dir / "secret-bindings.json").read_text(encoding="utf-8")))


def openbao_pod(identity: dict) -> None:
    """Допустить только закреплённый pod OpenBao своего кластера и дождаться его запуска."""
    scope = {**identity, "namespace": OPENBAO_NAMESPACE}
    namespace = json.loads(kubectl(scope, "get", "namespace", OPENBAO_NAMESPACE, "--ignore-not-found", "-o", "json") or "{}")
    if not namespace:
        raise ValueError("OpenBao namespace is missing; run the tls operation first")
    if namespace["metadata"].get("labels", {}).get(OWNER_LABEL) != identity["cluster"]:
        raise ValueError("OpenBao namespace belongs to another deployment")
    wanted = {"app.kubernetes.io/name": "openbao", "app.kubernetes.io/instance": "openbao", "component": "server"}
    deadline = time.monotonic() + POD_TIMEOUT
    while True:
        pod = json.loads(kubectl(scope, "get", "pod", POD, "--ignore-not-found", "-o", "json") or "{}")
        if pod:
            containers = {item["name"]: item for item in pod["spec"]["containers"]}
            if (not wanted.items() <= pod["metadata"].get("labels", {}).items()
                    or containers.get("openbao", {}).get("image") != expected_image()):
                raise ValueError("Pod is not the pinned OpenBao server of this chart")
            states = pod.get("status", {}).get("containerStatuses", [])
            if any(item["name"] == "openbao" and "running" in item.get("state", {}) for item in states):
                return
        if time.monotonic() >= deadline:
            raise RuntimeError("OpenBao pod is not running; install the Helm release first")
        time.sleep(3)


@contextlib.contextmanager
def connect(identity: dict, secret_dir: Path):
    """Проверенный клиент через `kubectl port-forward`, который туннелирует TLS, сохраняя его до сервера."""
    openbao_pod(identity)
    ca_file = private_source(secret_dir, TLS_FILES["ca.crt"])
    # Диагностика kubectl подавляется: сбой forward обозначается этапом, без необработанного вывода.
    with subprocess.Popen(["kubectl", "--kubeconfig", identity["kubeconfig"], "--context", identity["context"],
                           "--namespace", OPENBAO_NAMESPACE, "port-forward", "--address", "127.0.0.1",
                           f"pod/{POD}", f":{API_PORT}"],
                          stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True) as process:
        drain = None
        try:
            ready = select.select([process.stdout], [], [], 30)[0]
            match = re.search(r"127\.0\.0\.1:(\d+) ->", process.stdout.readline() if ready else "")
            if not match:
                raise RuntimeError("OpenBao port-forward did not start")
            # Без чтения pipe сообщения о соединениях заполнили бы буфер и заблокировали kubectl.
            drain = threading.Thread(target=lambda: [None for _ in process.stdout], daemon=True)
            drain.start()
            bao = Bao(int(match.group(1)), ca_file)
            eventually(lambda: bao.request("GET", "sys/seal-status"), "OpenBao API did not answer")
            yield bao
        finally:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            if drain:
                drain.join(timeout=5)


def context(args: argparse.Namespace) -> tuple[dict, Path]:
    return owned_identity(args.state_dir), require_private_source(args.secrets_dir)


def require_unsealed(bao: Bao) -> None:
    status = bao.request("GET", "sys/seal-status")
    if not status["initialized"] or status["sealed"]:
        raise ValueError("OpenBao is not initialized and unsealed; run bootstrap first")


def install_tls(args: argparse.Namespace) -> None:
    identity, secret_dir = context(args)
    files = {key: private_source(secret_dir, name) for key, name in TLS_FILES.items()}
    # Несовпадающий ключ иначе проявился бы лишь циклом падений серверного pod.
    ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER).load_cert_chain(files["tls.crt"], files["tls.key"])
    data = {key: base64.b64encode(path.read_bytes()).decode("ascii") for key, path in files.items()}
    scope = {**identity, "namespace": OPENBAO_NAMESPACE}
    labels = {OWNER_LABEL: identity["cluster"], "app.kubernetes.io/part-of": "bank11"}
    namespace = json.loads(kubectl(scope, "get", "namespace", OPENBAO_NAMESPACE, "--ignore-not-found", "-o", "json") or "{}")
    if namespace and namespace["metadata"].get("labels", {}).get(OWNER_LABEL) != identity["cluster"]:
        raise ValueError("OpenBao namespace belongs to another deployment")
    if not namespace:
        kubectl(scope, "create", "-f", "-", data=json.dumps({"apiVersion": "v1", "kind": "Namespace",
                "metadata": {"name": OPENBAO_NAMESPACE, "labels": labels}}).encode())
    current = json.loads(kubectl(scope, "get", "secret", TLS_SECRET, "--ignore-not-found", "-o", "json") or "{}")
    if current:
        if current["metadata"].get("labels", {}).get(OWNER_LABEL) != identity["cluster"]:
            raise ValueError("OpenBao TLS Secret ownership mismatch")
        if current.get("data", {}) != data:
            raise ValueError("Existing OpenBao TLS Secret differs; explicit certificate rotation is required")
    else:
        kubectl(scope, "create", "-f", "-", data=json.dumps({"apiVersion": "v1", "kind": "Secret",
                "type": "kubernetes.io/tls", "metadata": {"name": TLS_SECRET, "labels": labels},
                "data": data}).encode())
    print(f"OpenBao namespace and TLS Secret are ready in {OPENBAO_NAMESPACE}; existing values were preserved")


def initialize(bao: Bao, path: Path, cluster: str) -> None:
    """Однократная инициализация: файл восстановления резервируется заранее и публикуется только целиком."""
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    for directory in (path.parent, path.parent.parent):
        directory.chmod(0o700)
    pending = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    try:
        with os.fdopen(os.open(pending, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w", encoding="utf-8") as stream:
            # Одна доля Shamir: единственный оператор хранит её в том же зашифрованном источнике, что и root token.
            result = bao.request("POST", "sys/init", body={"secret_shares": 1, "secret_threshold": 1})
            try:
                json.dump({"version": 1, "cluster": cluster, "unseal_key": result["keys"][0],
                           "root_token": result["root_token"]}, stream)
                stream.flush()
                os.fsync(stream.fileno())
                os.link(pending, path)
            except (OSError, KeyError, IndexError, TypeError):
                raise RuntimeError("OpenBao was initialized but its recovery material was not saved; "
                                   "the OpenBao storage must be reset deliberately") from None
    finally:
        pending.unlink(missing_ok=True)


def unseal(bao: Bao, recovery: dict) -> None:
    if bao.request("GET", "sys/seal-status")["sealed"]:
        if bao.request("POST", "sys/unseal", body={"key": recovery["unseal_key"]})["sealed"]:
            raise RuntimeError("OpenBao stayed sealed after the recovery key was submitted")
    eventually(lambda: bao.request("GET", "sys/health"), "OpenBao did not become the active node")


def bootstrap(args: argparse.Namespace, *, may_initialize: bool = True) -> None:
    identity, secret_dir = context(args)
    path = recovery_path(secret_dir, identity["cluster"])
    with connect(identity, secret_dir) as bao:
        if not bao.request("GET", "sys/seal-status")["initialized"]:
            if not may_initialize:
                raise ValueError("OpenBao is not initialized; run bootstrap")
            if path.exists() or path.is_symlink():
                raise ValueError("Retained OpenBao recovery material exists but the server is uninitialized; "
                                 "restoring or resetting state is not supported")
            initialize(bao, path, identity["cluster"])
        elif not path.is_file():
            raise ValueError("OpenBao is initialized but its recovery file is absent; adoption is not supported")
        unseal(bao, load_recovery(path, identity["cluster"]))
    print("OpenBao is initialized, unsealed and active; recovery material stays in the external encrypted source")


def audit_devices(bao: Bao, token: str) -> dict:
    devices = unwrap(bao.request("GET", "sys/audit", token=token))
    return {name.rstrip("/"): device for name, device in devices.items() if isinstance(device, dict)}


def audit_is_safe(devices: dict) -> bool:
    """На ограниченном томе есть файловое устройство аудита; ни одно устройство не пишет значения без хеша."""
    options = [device.get("options") or {} for device in devices.values()]
    return (any(option.get("file_path") == AUDIT_FILE for option in options)
            and all(option.get("log_raw", "false") != "true" and option.get("hmac_accessor", "true") != "false"
                    for option in options))


def ensure_mount(bao: Bao, token: str, kind: str, name: str, mount_type: str, options: dict) -> None:
    """Включить движок или метод входа один раз; существующий mount другого типа не менять и не присваивать."""
    existing = unwrap(bao.request("GET", f"sys/{kind}", token=token)).get(f"{name}/")
    if existing is None:
        bao.request("POST", f"sys/{kind}/{name}", token=token,
                    body={"type": mount_type, **({"options": options} if options else {})})
    elif existing.get("type") != mount_type or any((existing.get("options") or {}).get(key) != value
                                                  for key, value in options.items()):
        raise ValueError(f"Mount {name}/ exists with another type or version; adoption is not supported")


def configure(args: argparse.Namespace) -> None:
    identity, secret_dir = context(args)
    namespace = identity["namespace"]
    roles = read_bindings(args.state_dir, namespace)
    recovery = load_recovery(recovery_path(secret_dir, identity["cluster"]), identity["cluster"])
    token = recovery["root_token"]
    with connect(identity, secret_dir) as bao:
        require_unsealed(bao)
        # Без работающего HMAC-аудита из Helm values дальнейшая настройка запрещена.
        eventually(lambda: audit_is_safe(audit_devices(bao, token)),
                   "OpenBao has no HMAC file audit device on the audit volume")
        ensure_mount(bao, token, "mounts", KV_MOUNT, "kv", {"version": "2"})
        ensure_mount(bao, token, "auth", AUTH_MOUNT, "kubernetes", {})
        # Вход проверяют токен pod и CA самого сервера: отдельный reviewer JWT в OpenBao не хранится.
        bao.request("POST", f"auth/{AUTH_MOUNT}/config", token=token, body={"kubernetes_host": KUBERNETES_HOST})
        for role, entry in roles.items():
            bao.request("POST", f"sys/policies/acl/{role}", token=token, body={"policy": policy_text(entry["paths"], inspect_mount=entry["account"] == ESO_ACCOUNT)})
            bao.request("POST", f"auth/{AUTH_MOUNT}/role/{role}", token=token,
                        body=role_payload(namespace, role, entry["account"]))
    print(f"Configured KV v2 at {KV_MOUNT}/, Kubernetes auth and {len(roles)} exact-path read roles; no secret values handled")


def store(bao: Bao, token: str, path: str, values: dict) -> str:
    """Импорт добавляет отсутствующие ключи; существующее отличающееся значение не перезаписывается."""
    location = f"{KV_MOUNT}/data/{path}"
    current = bao.find(location, token)
    if current is None:
        bao.request("POST", location, token=token, body={"options": {"cas": 0}, "data": values})
        return "created"
    stored, version = current["data"]["data"], current["data"]["metadata"]["version"]
    changed = sorted(key for key in values if key in stored and stored[key] != values[key])
    if changed:
        raise ValueError(f"Existing OpenBao secret differs; explicit credential rotation is required: {path} {changed}")
    missing = {key: value for key, value in values.items() if key not in stored}
    if not missing:
        return "unchanged"
    bao.request("PATCH", location, token=token, body={"options": {"cas": version}, "data": missing},
                content_type="application/merge-patch+json")
    return "extended"


def import_secrets(args: argparse.Namespace) -> None:
    identity, secret_dir = context(args)
    roles = read_bindings(args.state_dir, identity["namespace"])
    recovery = load_recovery(recovery_path(secret_dir, identity["cluster"]), identity["cluster"])
    # Все источники проверяются до первой записи.
    imports = {path: {target: secret_text(secret_dir, source) for target, source in files.items()}
               for entry in roles.values() for path, files in entry["paths"].items()}
    outcomes = []
    with connect(identity, secret_dir) as bao:
        require_unsealed(bao)
        if f"{KV_MOUNT}/" not in unwrap(bao.request("GET", "sys/mounts", token=recovery["root_token"])):
            raise ValueError("KV mount is missing; run configure first")
        for path, values in imports.items():
            outcomes.append(store(bao, recovery["root_token"], path, values))
    print(f"Imported {len(imports)} paths ({', '.join(sorted(set(outcomes)))}); values were not printed")


def service_token(identity: dict, namespace: str, account: str, audience: str | None = None) -> str:
    arguments = ["create", "token", account, "--duration", "10m", *(["--audience", audience] if audience else [])]
    try:
        return kubectl({**identity, "namespace": namespace}, *arguments).strip()
    except RuntimeError:
        raise RuntimeError(f"ServiceAccount {account} cannot issue a token in {namespace}; "
                           "the chart must create it") from None


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(f"OpenBao verification failed: {message}")


def rejects_login(bao: Bao, role: str, jwt: str) -> bool:
    try:
        auth = bao.request("POST", f"auth/{AUTH_MOUNT}/login", body={"role": role, "jwt": jwt})["auth"]
    except BaoError as error:
        if error.status in DENIED:
            return True
        raise
    bao.request("POST", "auth/token/revoke-self", token=auth["client_token"])  # no unintended credential survives
    return False


def check_configuration(bao: Bao, token: str, namespace: str, roles: dict) -> None:
    require(audit_is_safe(audit_devices(bao, token)), "audit device is missing or logs raw values")
    keys = unwrap(bao.request("LIST", f"auth/{AUTH_MOUNT}/role", token=token)).get("keys", [])
    require(sorted(keys) == sorted(roles), f"unmanaged or missing roles {sorted(set(keys) ^ set(roles))}")
    for role, entry in roles.items():
        wanted = role_payload(namespace, role, entry["account"])
        stored = unwrap(bao.request("GET", f"auth/{AUTH_MOUNT}/role/{role}", token=token))
        require({key: stored.get(key) for key in wanted} == wanted, f"role {role} drifted")
        stored = unwrap(bao.request("GET", f"sys/policies/acl/{role}", token=token))
        require(stored.get("policy", "").strip() == policy_text(entry["paths"], inspect_mount=entry["account"] == ESO_ACCOUNT).strip(), f"policy {role} drifted")


def check_role(bao: Bao, identity: dict, roles: dict, role: str, tokens: dict) -> None:
    namespace, account = identity["namespace"], roles[role]["account"]
    owned = roles[role]["paths"]
    foreign = sorted(path for other in roles.values() for path in other["paths"] if path not in owned)
    auth = bao.request("POST", f"auth/{AUTH_MOUNT}/login", body={"role": role, "jwt": tokens[account]})["auth"]
    token = auth["client_token"]
    try:
        require(auth["renewable"] and 0 < auth["lease_duration"] <= TOKEN_TTL
                and sorted(auth["policies"]) == sorted(["default", role]), f"{role} token shape")
        for path, files in owned.items():
            # Spring ConfigData сначала определяет версию KV по внутреннему mount endpoint.
            mount = unwrap(bao.request("GET", f"sys/internal/ui/mounts/{KV_MOUNT}/{path}", token=token))
            require(mount.get("type") == "kv" and mount.get("options", {}).get("version") == "2", f"{role} KV v2 lookup")
            stored = bao.request("GET", f"{KV_MOUNT}/data/{path}", token=token)["data"]["data"]
            require(set(files) <= set(stored), f"{role} cannot read every imported key of {path}")
        for path in foreign:
            require(bao.denied("GET", f"{KV_MOUNT}/data/{path}", token=token), f"{role} read {path}")
        # Проба запрашивает capabilities, не выполняя их: ошибка политики не должна приводить к записи.
        expected = {}
        for path in owned:
            expected[f"{KV_MOUNT}/data/{path}"] = ["read"]
            expected[f"{KV_MOUNT}/metadata/{path}"] = ["deny"]
            expected[f"{KV_MOUNT}/data/{path}/child"] = ["deny"]
        asked = unwrap(bao.request("POST", "sys/capabilities-self", token=token, body={"paths": list(expected)}))
        for probe, capabilities in expected.items():
            require(asked.get(probe) == capabilities, f"{role} capabilities on {probe}")
        renewed = bao.request("POST", "auth/token/renew-self", token=token)["auth"]
        require(renewed["renewable"] and 0 < renewed["lease_duration"] <= TOKEN_TTL, f"{role} renewal")
    finally:
        bao.request("POST", "auth/token/revoke-self", token=token)
    require(bao.denied("GET", "auth/token/lookup-self", token=token), f"{role} token survived revocation")


def check_login_boundaries(bao: Bao, identity: dict, roles: dict, role: str, tokens: dict) -> None:
    account = roles[role]["account"]
    others = [name for name in tokens if name != account]
    if others:
        require(rejects_login(bao, role, tokens[others[0]]), f"{role} accepted another service account")
    wrong_audience = service_token(identity, identity["namespace"], account)
    require(rejects_login(bao, role, wrong_audience), f"{role} accepted the default API audience")
    wrong_namespace = service_token(identity, OPENBAO_NAMESPACE, account, AUDIENCE)
    require(rejects_login(bao, role, wrong_namespace), f"{role} accepted another namespace")


def seal_drill(bao: Bao, token: str, recovery: dict, jwt: str, role: str) -> None:
    """Проверить отказ в обслуживании после seal и обязательно выполнить unseal."""
    bao.request("POST", "sys/seal", token=token)
    try:
        for method, path, body in (("GET", "sys/health", None), ("POST", f"auth/{AUTH_MOUNT}/login", {"role": role, "jwt": jwt})):
            try:
                bao.request(method, path, body=body)
            except BaoError as error:
                require(error.status == 503, f"sealed server answered {error.status} to {path}")
            else:
                require(False, f"sealed server served {path}")
    finally:
        unseal(bao, recovery)


def verify(args: argparse.Namespace) -> None:
    identity, secret_dir = context(args)
    namespace = identity["namespace"]
    roles = read_bindings(args.state_dir, namespace)
    recovery = load_recovery(recovery_path(secret_dir, identity["cluster"]), identity["cluster"])
    accounts = sorted({entry["account"] for entry in roles.values()})
    scope = {**identity, "namespace": OPENBAO_NAMESPACE}
    labels = {VERIFY_LABEL: uuid.uuid4().hex, OWNER_LABEL: identity["cluster"]}
    with connect(identity, secret_dir) as bao:
        require_unsealed(bao)
        check_configuration(bao, recovery["root_token"], namespace, roles)
        try:
            # Одноимённые учётные записи другого namespace отдельно проверяют привязку к namespace.
            kubectl(scope, "create", "-f", "-", data=json.dumps({"apiVersion": "v1", "kind": "List", "items": [
                {"apiVersion": "v1", "kind": "ServiceAccount", "metadata": {"name": account, "labels": labels}}
                for account in accounts]}).encode())
            tokens = {account: service_token(identity, namespace, account, AUDIENCE) for account in accounts}
            for role in sorted(roles):
                check_role(bao, identity, roles, role, tokens)
                check_login_boundaries(bao, identity, roles, role, tokens)
        finally:
            # Удаляются только учётные записи с меткой этого запуска.
            kubectl(scope, "delete", "serviceaccounts", "-l", f"{VERIFY_LABEL}={labels[VERIFY_LABEL]}", "--wait=true")
        if args.exercise_seal:
            role = sorted(roles)[0]
            seal_drill(bao, recovery["root_token"], recovery, tokens[roles[role]["account"]], role)
    print(f"Verified {len(roles)} roles: own read, cross-service deny, exact capabilities, audience/name/namespace "
          f"denial, renew and revoke" + ("; sealed behavior exercised" if args.exercise_seal else ""))


def snapshot(args: argparse.Namespace) -> None:
    identity, secret_dir = context(args)
    path = recovery_path(secret_dir, identity["cluster"])
    recovery = load_recovery(path, identity["cluster"])
    directory = path.parent / "snapshots"
    directory.mkdir(exist_ok=True, mode=0o700)
    directory.chmod(0o700)
    target = directory / time.strftime("raft-%Y%m%dT%H%M%SZ.snap", time.gmtime())
    with connect(identity, secret_dir) as bao:
        require_unsealed(bao)
        descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        try:
            with os.fdopen(descriptor, "wb") as stream:
                bao.request("GET", "sys/storage/raft/snapshot", token=recovery["root_token"], sink=stream)
                stream.flush()
                os.fsync(stream.fileno())
        except BaseException:
            target.unlink(missing_ok=True)
            raise
    print(f"Saved a Raft snapshot beside the recovery file ({target.stat().st_size} bytes); it is unusable without the recovery key")


OPERATIONS = {"tls": install_tls, "bootstrap": bootstrap, "configure": configure, "import": import_secrets,
              "verify": verify, "snapshot": snapshot,
              "unseal": lambda args: bootstrap(args, may_initialize=False)}


def main() -> None:
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state-dir", required=True, type=Path)
    subparsers = parser.add_subparsers(dest="operation", required=True)
    for name in OPERATIONS:
        subparsers.add_parser(name).add_argument("--secrets-dir", type=Path, required=True)
    subparsers.choices["verify"].add_argument("--exercise-seal", action="store_true")
    args = parser.parse_args()
    OPERATIONS[args.operation](args)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print(f"FAIL {error}", file=sys.stderr)
        sys.exit(1)
