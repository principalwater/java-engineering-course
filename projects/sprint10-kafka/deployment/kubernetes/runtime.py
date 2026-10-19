"""Operate only the explicitly owned local kind cluster; preserve all persistent data."""

import argparse
import base64
import hashlib
import importlib.util
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import time
import uuid

import yaml

from prepare import APPS, image_reference, prepare, project_dir, require_external


NODE_IMAGE = "kindest/node:v1.34.11@sha256:44e222ee2132dab25ff87301682f89eb82c7880ea3a1bf543bfe9708fd08d67d"
OWNER_LABEL = "bank10.io/cluster"
# Docker exports/unpacked layers need temporary headroom; this floor is not a capacity estimate.
MIN_HOST_DISK_FREE_GIB = 5


def command(arguments: list[str], *, data: bytes | None = None, timeout: int = 120,
            environment: dict | None = None) -> str:
    completed = subprocess.run(arguments, input=data, stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, timeout=timeout, env=environment)
    if completed.returncode:
        # CLI errors may echo submitted Secret bodies; diagnostics name only the phase.
        raise RuntimeError(f"Command failed: {arguments[0]} {arguments[1]} (exit {completed.returncode})")
    return completed.stdout.decode("utf-8")


def private_file(path: Path, data: bytes) -> None:
    if path.is_symlink():
        raise ValueError("Private files must not be symlinks")
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())


def save_receipt(path: Path, receipt: dict) -> None:
    if path.is_symlink():
        raise ValueError("Operational receipts must not be symlinks")
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    private_file(temporary, (json.dumps(receipt, indent=2) + "\n").encode())
    temporary.replace(path)


def record_nodes(intent: dict, expected: list[str]) -> None:
    nodes = command(["docker", "ps", "-a", "--filter", f"label=io.x-k8s.kind.cluster={intent['cluster']}",
                     "--format", "{{.Names}}"] ).split()
    if not set(nodes).issubset(expected):
        raise ValueError("Unexpected container is using the intended kind identity")
    for node in nodes:
        networks = json.loads(command(["docker", "inspect", "--format", "{{json .NetworkSettings.Networks}}", node]))
        if not any(network["NetworkID"] == intent["network_id"] for network in networks.values()):
            raise ValueError("Kind node is not attached to the exact owned network")
        node_id = command(["docker", "inspect", "--format", "{{.Id}}", node]).strip()
        if node in intent["nodes"] and intent["nodes"][node] != node_id:
            raise ValueError("Previously recorded kind node was replaced")
        intent["nodes"][node] = node_id


def require_private_source(directory: Path) -> Path:
    if not directory.is_dir() or directory.is_symlink():
        raise ValueError("An unlocked external secret directory is required")
    directory = directory.resolve()
    if directory.is_relative_to(project_dir().parents[1]) or directory.stat().st_mode & 0o077:
        raise ValueError("Secret directory must be private and outside the repository")
    if sys.platform == "darwin":
        specification = importlib.util.spec_from_file_location("bank_secret_disk", project_dir() / "deployment/scripts/secret-disk.py")
        disk = importlib.util.module_from_spec(specification)
        specification.loader.exec_module(disk)
        image = disk.disk_path(directory)
        if not image.is_file() or disk.mounted_image(directory) != image.resolve():
            raise ValueError("Secret directory must be an unlocked encrypted disk mount")
        disk.require_encryption(image, disk.keychain_password(image))
    elif os.environ.get("BANK_ALLOW_PLAINTEXT_TEST_SECRETS") != "true":
        raise ValueError("Linux local file sources are allowed only in isolated CI")
    return directory


def kubectl(identity: dict, *arguments: str, data: bytes | None = None, timeout: int = 120) -> str:
    return command(["kubectl", "--kubeconfig", identity["kubeconfig"], "--context", identity["context"],
                    "--namespace", identity["namespace"], *arguments], data=data, timeout=timeout)


def owned_identity(state_dir: Path, *, api: bool = True) -> dict:
    identity_path = state_dir / "cluster.json"
    if not identity_path.is_file() or identity_path.is_symlink():
        raise ValueError("Owned cluster identity is missing; adoption is not supported")
    identity = json.loads(identity_path.read_text(encoding="utf-8"))
    cluster_match = re.fullmatch(r"bank10-(dev|test|prod)-(single|ha)", identity["cluster"])
    if not cluster_match:
        raise ValueError("Cluster identity is outside the bank10 allowlist")
    if identity["namespace"] != f"bank10-{cluster_match.group(1)}" or identity["profile"] != cluster_match.group(2):
        raise ValueError("Namespace/profile do not match the owned cluster identity")
    if identity["context"] != f"kind-{identity['cluster']}":
        raise ValueError("Unexpected Kubernetes context")
    nodes = command(["kind", "get", "nodes", "--name", identity["cluster"]]).split()
    if set(nodes) != set(identity["nodes"]):
        raise ValueError("Owned kind node inventory changed")
    for node in nodes:
        inspected = json.loads(command(["docker", "inspect", "--format", "{{json .Config.Labels}}", node]))
        node_id = command(["docker", "inspect", "--format", "{{.Id}}", node]).strip()
        if inspected.get("io.x-k8s.kind.cluster") != identity["cluster"] or node_id != identity["nodes"][node]:
            raise ValueError("Kind node ownership changed")
        networks = json.loads(command(["docker", "inspect", "--format", "{{json .NetworkSettings.Networks}}", node]))
        if not any(network.get("NetworkID") == identity["network_id"] for network in networks.values()):
            raise ValueError("Kind node is outside the exact owned network")
    if api:
        uid = kubectl(identity, "get", "namespace", "kube-system", "-o", "jsonpath={.metadata.uid}").strip()
        if uid != identity["cluster_uid"]:
            raise ValueError("Kubernetes cluster UID changed")
    return identity


def encryption_config(path: Path) -> None:
    if path.exists():
        if not path.is_file() or path.is_symlink() or path.stat().st_mode & 0o077:
            raise ValueError("Existing encryption configuration is not a private regular file")
        existing = yaml.safe_load(path.read_text(encoding="utf-8"))
        providers = existing.get("resources", [{}])[0].get("providers", [])
        if not providers or "aescbc" not in providers[0]:
            raise ValueError("Existing encryption configuration must encrypt Secrets first")
        keys = providers[0]["aescbc"].get("keys", [])
        if not keys or len(base64.b64decode(keys[0].get("secret", ""), validate=True)) != 32:
            raise ValueError("Existing encryption configuration must contain a 256-bit AES key")
        return
    key = base64.b64encode(secrets.token_bytes(32)).decode("ascii")
    document = {"apiVersion": "apiserver.config.k8s.io/v1", "kind": "EncryptionConfiguration",
                "resources": [{"resources": ["secrets"], "providers": [
                    {"aescbc": {"keys": [{"name": "bank10-v1", "secret": key}]}}, {"identity": {}}]}]}
    private_file(path, yaml.safe_dump(document).encode())


def create_cluster(args: argparse.Namespace) -> None:
    state_dir = require_external(args.state_dir, project_dir().parents[1])
    secret_dir = require_private_source(args.secrets_dir)
    cluster = f"bank10-{args.environment}-{args.profile}"
    if (state_dir / "cluster.json").exists():
        identity = owned_identity(state_dir, api=False)
        if identity["cluster"] != cluster:
            raise ValueError("State directory belongs to another profile")
        print("Owned cluster already exists; use resume without recreating its nodes")
        return
    intent_path = state_dir / "creation.json"
    intent = json.loads(intent_path.read_text(encoding="utf-8")) if intent_path.is_file() else None
    if intent and (intent["cluster"] != cluster or intent["secrets_dir"] != str(secret_dir)):
        raise ValueError("Existing creation intent belongs to another deployment")
    if not intent and cluster in command(["kind", "get", "clusters"]).split():
        raise ValueError("Existing cluster has no local ownership receipt; refusing adoption")
    if sys.platform == "darwin" and "FileVault is On" not in command(["fdesetup", "status"]):
        raise ValueError("Persistent local kind storage requires FileVault encryption at rest")
    if sys.platform != "darwin" and os.environ.get("BANK_ALLOW_PLAINTEXT_TEST_SECRETS") != "true":
        raise ValueError("Use an encrypted production StorageClass; local hostPath is CI-only on Linux")
    subnets = {"dev-single": "10.251.10.64/27", "dev-ha": "10.251.10.96/27",
               "test-single": "10.251.10.128/27", "test-ha": "10.251.10.160/27",
               "prod-single": "10.251.10.192/27", "prod-ha": "10.251.10.224/27"}
    subnet = ipaddress.ip_network(subnets[f"{args.environment}-{args.profile}"])
    networks = command(["docker", "network", "ls", "-q"]).split()
    for network in networks:
        info = json.loads(command(["docker", "network", "inspect", network]))[0]
        if info["Name"] == cluster:
            if not intent or info.get("Labels", {}).get(OWNER_LABEL) != cluster:
                raise ValueError("Existing network has no ownership intent; refusing adoption")
            if intent.get("network_id") and info["Id"] != intent["network_id"]:
                raise ValueError("Previously recorded owned network was replaced")
            if not intent.get("network_id") and info.get("Labels", {}).get("bank10.io/intent") != intent["id"]:
                raise ValueError("Network does not carry the exact creation intent")
            intent["network_id"] = info["Id"]
            continue
        for config in info.get("IPAM", {}).get("Config") or []:
            if config.get("Subnet"):
                existing = ipaddress.ip_network(config["Subnet"])
                if existing.version == subnet.version and subnet.overlaps(existing):
                    raise ValueError("Dedicated kind network subnet overlaps an existing network")
    private_dir = secret_dir / "kubernetes" / cluster
    private_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    private_dir.chmod(0o700)
    encryption_path = private_dir / "encryption.yaml"
    encryption_config(encryption_path)
    kubeconfig = private_dir / "kubeconfig"
    if kubeconfig.exists() and not intent:
        raise ValueError("An existing kubeconfig must be reconciled before creating new cluster identity")
    node_names = [f"{cluster}-control-plane"]
    if args.profile == "ha":
        node_names.extend(f"{cluster}-worker{suffix}" for suffix in ("", "2"))
    nodes = []
    for index, name in enumerate(node_names):
        data_dir = state_dir / "pvc" / name
        data_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        node = {"role": "control-plane" if index == 0 else "worker", "image": NODE_IMAGE,
                "labels": {"bank10.io/worker": "true"},
                "extraMounts": [{"hostPath": str(data_dir), "containerPath": "/var/local-path-provisioner"}]}
        if index == 0:
            if args.profile == "ha":
                node["kubeadmConfigPatches"] = [yaml.safe_dump({"kind": "InitConfiguration",
                                                               "nodeRegistration": {"taints": []}})]
            node["extraMounts"].append({"hostPath": str(encryption_path),
                                        "containerPath": "/etc/kubernetes/bank10-encryption.yaml", "readOnly": True})
            node["extraPortMappings"] = [{"containerPort": 30450, "hostPort": args.external_port,
                                           "listenAddress": "127.0.0.1", "protocol": "TCP"}]
        nodes.append(node)
    patch = {"apiVersion": "kubeadm.k8s.io/v1beta3", "kind": "ClusterConfiguration", "apiServer": {
        "extraArgs": {"encryption-provider-config": "/etc/kubernetes/bank10-encryption.yaml"},
        "extraVolumes": [{"name": "bank-encryption", "hostPath": "/etc/kubernetes/bank10-encryption.yaml",
                           "mountPath": "/etc/kubernetes/bank10-encryption.yaml", "readOnly": True, "pathType": "File"}]}}
    config = {"kind": "Cluster", "apiVersion": "kind.x-k8s.io/v1alpha4",
              "networking": {"apiServerAddress": "127.0.0.1"},
              "kubeadmConfigPatches": [yaml.safe_dump(patch)], "nodes": nodes}
    config_path = state_dir / "kind.yaml"
    config_path.write_text(yaml.safe_dump(config), encoding="utf-8")
    config_sha = hashlib.sha256(config_path.read_bytes()).hexdigest()
    if intent and intent["config_sha256"] != config_sha:
        raise ValueError("Creation topology changed; retained resources require explicit reconciliation")
    if not intent:
        intent = {"id": uuid.uuid4().hex, "cluster": cluster, "config_sha256": config_sha,
                  "secrets_dir": str(secret_dir), "network_id": None, "nodes": {}}
    save_receipt(intent_path, intent)
    if not intent["network_id"]:
        intent["network_id"] = command(["docker", "network", "create", "--subnet", str(subnet),
                                         "--label", f"{OWNER_LABEL}={cluster}",
                                         "--label", f"bank10.io/intent={intent['id']}", cluster]).strip()
        save_receipt(intent_path, intent)
    environment = dict(os.environ, KIND_EXPERIMENTAL_DOCKER_NETWORK=cluster)
    record_nodes(intent, node_names)
    if not intent["nodes"]:
        result = subprocess.run(["kind", "create", "cluster", "--name", cluster, "--config", str(config_path),
                                 "--kubeconfig", str(kubeconfig), "--wait", "180s", "--retain"],
                                capture_output=True, timeout=300, env=environment)
        record_nodes(intent, node_names)
        save_receipt(intent_path, intent)
        if result.returncode:
            safe_lines = [line for line in result.stderr.decode(errors="replace").splitlines()
                          if "error" in line.lower() and not re.search(r"token|password|secret|PRIVATE KEY", line, re.I)]
            (state_dir / "kind-error.log").write_text("\n".join(safe_lines)[-4000:] + "\n", encoding="utf-8")
            raise RuntimeError("Kind creation failed; exact owned resources retained, see private kind-error.log")
    if set(intent["nodes"]) != set(node_names) or not kubeconfig.is_file():
        raise RuntimeError("Owned partial cluster retained; repair bootstrap before retrying")
    kubeconfig.chmod(0o600)
    identity = {"cluster": cluster, "context": f"kind-{cluster}", "namespace": f"bank10-{args.environment}",
                "kubeconfig": str(kubeconfig), "profile": args.profile, "external_port": args.external_port,
                "network_id": intent["network_id"], "nodes": intent["nodes"]}
    identity["cluster_uid"] = kubectl(identity, "get", "namespace", "kube-system", "-o", "jsonpath={.metadata.uid}").strip()
    private_file(state_dir / "cluster.json", (json.dumps(identity, indent=2) + "\n").encode())
    print(f"Created owned cluster {cluster}; API loopback, encrypted Secrets, retained host storage")


def bootstrap_secrets(args: argparse.Namespace) -> None:
    identity = owned_identity(args.state_dir)
    secret_dir = require_private_source(args.secrets_dir)
    bindings = json.loads((args.state_dir / "secret-bindings.json").read_text(encoding="utf-8"))
    namespace = json.loads(kubectl(identity, "get", "namespace", identity["namespace"], "--ignore-not-found", "-o", "json") or "{}")
    labels = {OWNER_LABEL: identity["cluster"], "app.kubernetes.io/part-of": "bank10"}
    if namespace and namespace["metadata"].get("labels", {}).get(OWNER_LABEL) != identity["cluster"]:
        raise ValueError("Namespace belongs to another deployment")
    if not namespace:
        kubectl(identity, "create", "-f", "-", data=json.dumps({"apiVersion": "v1", "kind": "Namespace",
                "metadata": {"name": identity["namespace"], "labels": labels}}).encode())
    for name, files in bindings.items():
        if not re.fullmatch(r"bank10-[a-z-]+", name):
            raise ValueError("Unexpected Secret object name")
        data = {}
        for target, source in files.items():
            if not re.fullmatch(r"[a-zA-Z0-9_.-]+", target) or not re.fullmatch(r"[a-zA-Z0-9_.-]+", source):
                raise ValueError("Secret projection filenames must not contain paths")
            path = secret_dir / source
            if not path.is_file() or path.is_symlink() or path.stat().st_mode & 0o077:
                raise ValueError(f"Secret source is absent or not private: {source}")
            data[target] = base64.b64encode(path.read_bytes()).decode("ascii")
        current = json.loads(kubectl(identity, "get", "secret", name, "--ignore-not-found", "-o", "json") or "{}")
        if current:
            if current["metadata"].get("labels", {}).get(OWNER_LABEL) != identity["cluster"]:
                raise ValueError(f"Secret ownership mismatch: {name}")
            if current.get("data", {}) != data:
                raise ValueError(f"Existing Secret differs; explicit credential rotation is required: {name}")
            continue
        kubectl(identity, "create", "-f", "-", data=json.dumps({"apiVersion": "v1", "kind": "Secret", "type": "Opaque",
                "metadata": {"name": name, "labels": labels}, "data": data}).encode())
    print(f"Bootstrapped {len(bindings)} owned Secret references; existing values preserved, no Terraform Secret resources")


def require_host_disk_space() -> None:
    if shutil.disk_usage(project_dir()).free < MIN_HOST_DISK_FREE_GIB * 1024 ** 3:
        raise ValueError(f"Image operations require at least {MIN_HOST_DISK_FREE_GIB} GiB free host disk; "
                         "this lower bound does not guarantee capacity")


def load_images(args: argparse.Namespace) -> None:
    identity = owned_identity(args.state_dir)
    project = project_dir()
    source = json.loads((args.state_dir / "source.json").read_text(encoding="utf-8"))
    services = [*APPS[:-1], "authorization", "edge"]
    if source["analytics"]:
        services.append("analytics")
    if source["superset"]:
        services.extend(["superset", "bank-bi"])
    images = {}
    for name in services:
        require_host_disk_space()
        image = image_reference(project, name)
        if name in APPS or name == "bank-bi":
            command(["docker", "build", "--tag", image, "--build-arg", f"SERVICE={name}", str(project)], timeout=300)
        else:
            context = project / "deployment" / name
            command(["docker", "build", "--tag", image, str(context)], timeout=300)
        require_host_disk_space()
        command(["kind", "load", "docker-image", "--name", identity["cluster"], image], timeout=300)
        images[name] = image
    (args.state_dir / "images.json").write_text(json.dumps(images, indent=2) + "\n", encoding="utf-8")
    prepare(args.state_dir, environment=identity["namespace"].removeprefix("bank10-"), profile=identity["profile"],
            analytics=source["analytics"], superset=source["superset"], external_port=identity["external_port"], images=images)
    print(f"Loaded {len(images)} content-addressed application images; no Gradle invocation")


def ready(args: argparse.Namespace) -> None:
    identity = owned_identity(args.state_dir)
    deadline = time.monotonic() + args.timeout
    expected = [*APPS[:-1], "authorization", "edge"]
    source = json.loads((args.state_dir / "source.json").read_text(encoding="utf-8"))
    if source["analytics"]:
        expected.append("analytics")
    if source["superset"]:
        expected.append("superset")
    manifest = command(["helm", "get", "manifest", "bank", "--kubeconfig", identity["kubeconfig"],
                        "--kube-context", identity["context"], "--namespace", identity["namespace"]])
    expected_jobs = {item["metadata"]["name"] for item in yaml.safe_load_all(manifest)
                     if item and item.get("kind") == "Job"}
    if not expected_jobs:
        raise ValueError("Current bank release has no finite initialization Jobs")
    while time.monotonic() < deadline:
        deployments = json.loads(kubectl(identity, "get", "deployments", "-o", "json"))["items"]
        selected = [item for item in deployments if item["metadata"]["name"] in expected]
        if any(item["spec"].get("replicas", 1) != 1 or item["spec"].get("strategy", {}).get("type") != "Recreate"
               for item in selected):
            raise ValueError("Bank applications require one replica and Recreate for the maintenance cutover")
        if json.loads(kubectl(identity, "get", "hpa", "-o", "json"))["items"]:
            raise ValueError("Autoscaling is outside the bank maintenance cutover contract")
        ready_names = {item["metadata"]["name"] for item in deployments if item.get("status", {}).get("availableReplicas", 0) >= 1}
        kafka = json.loads(kubectl(identity, "get", "kafka", "bank-kafka", "-o", "json"))
        kafka_ready = any(item["type"] == "Ready" and item["status"] == "True" for item in kafka.get("status", {}).get("conditions", []))
        jobs = {item["metadata"]["name"]: item for item in json.loads(kubectl(identity, "get", "jobs", "-l", "app.kubernetes.io/part-of=bank10", "-o", "json"))["items"]}
        if kafka_ready and set(expected).issubset(ready_names) and expected_jobs.issubset(jobs) and all(jobs[name].get("status", {}).get("succeeded", 0) for name in expected_jobs):
            print(f"Owned {identity['profile']} bank is ready; Kafka and {len(expected)} Deployments, finite Jobs complete")
            return
        time.sleep(3)
    raise RuntimeError("Owned bank readiness timed out; inspect safe pod state before stopping")


def lifecycle(args: argparse.Namespace, *, stop: bool) -> None:
    identity = owned_identity(args.state_dir, api=stop)
    nodes = list(identity["nodes"])
    if not stop:
        if not Path(identity["kubeconfig"]).is_file():
            raise ValueError("Unlock the cluster AES secret source before resuming")
        command(["docker", "start", *nodes])
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            result = subprocess.run(["kubectl", "--kubeconfig", identity["kubeconfig"], "--context", identity["context"],
                                     "get", "--raw=/readyz"], capture_output=True, timeout=5)
            if result.returncode == 0:
                break
            time.sleep(2)
        owned_identity(args.state_dir)
    if stop:
        node_state = json.loads(kubectl(identity, "get", "nodes", "-o", "json"))["items"]
        if {node["metadata"]["name"] for node in node_state} != set(nodes):
            raise ValueError("Kubernetes node inventory does not match the owned nodes")
        previous = {node["metadata"]["name"]: node.get("spec", {}).get("unschedulable", False) for node in node_state}
        snapshot = args.state_dir / "scheduling.json"
        if snapshot.exists():
            raise ValueError("An unfinished stop/resume scheduling receipt already exists")
        private_file(snapshot, (json.dumps(previous) + "\n").encode())
        kubectl(identity, "cordon", *nodes)
        # Cordon keeps replacement Pods pending without changing Helm-declared replica counts.
        for role in ("application", "storage", "kafka"):
            kubectl(identity, "delete", "pods", "-l", f"bank10.io/role={role}",
                    "--wait=true", "--timeout=120s", timeout=150)
        command(["docker", "stop", "--time", "60", *reversed(nodes)], timeout=300)
        print("Stopped owned applications before stores and kind nodes; PVCs/state/identities retained")
    else:
        snapshot = args.state_dir / "scheduling.json"
        if snapshot.is_file():
            previous = json.loads(snapshot.read_text(encoding="utf-8"))
            if set(previous) != set(nodes):
                raise ValueError("Scheduling receipt does not match owned nodes")
            for node, cordoned in previous.items():
                kubectl(identity, "cordon" if cordoned else "uncordon", node)
            snapshot.unlink()
        print("Resumed only the retained owned nodes and bank workloads; completed Jobs preserved")


def main() -> None:
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state-dir", required=True, type=Path)
    subparsers = parser.add_subparsers(dest="operation", required=True)
    create = subparsers.add_parser("create")
    create.add_argument("--secrets-dir", type=Path, required=True)
    create.add_argument("--environment", choices=("dev", "test", "prod"), default="dev")
    create.add_argument("--profile", choices=("single", "ha"), default="single")
    create.add_argument("--external-port", type=int, default=8450)
    bootstrap = subparsers.add_parser("secrets")
    bootstrap.add_argument("--secrets-dir", type=Path, required=True)
    subparsers.add_parser("load-images")
    readiness = subparsers.add_parser("ready")
    readiness.add_argument("--timeout", type=int, default=900)
    subparsers.add_parser("stop")
    subparsers.add_parser("resume")
    args = parser.parse_args()
    if args.operation == "create":
        create_cluster(args)
    elif args.operation == "secrets":
        bootstrap_secrets(args)
    elif args.operation == "load-images":
        load_images(args)
    elif args.operation == "ready":
        ready(args)
    else:
        lifecycle(args, stop=args.operation == "stop")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print(f"FAIL {error}", file=sys.stderr)
        sys.exit(1)
