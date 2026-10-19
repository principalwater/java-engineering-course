"""Create and apply a reviewed bank-only Helm plan without reading Secret data into OpenTofu."""

import argparse
import base64
import hashlib
import importlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys

import yaml


EXPECTED_RESOURCES = {"helm_release.bank", "helm_release.strimzi", "data.kubernetes_namespace_v1.bank"}


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def infrastructure_digest(root: Path) -> str:
    hashed = hashlib.sha256()
    for path in sorted([*root.glob("*.tf"), root / ".terraform.lock.hcl"]):
        hashed.update(path.name.encode() + b"\0" + path.read_bytes())
    return hashed.hexdigest()


def encoded_secret_values(raw: bytes) -> set[bytes]:
    values = {raw, raw.strip(), base64.b64encode(raw), base64.b64encode(raw.strip())}
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError:
        return values
    values.update((json.dumps(text)[1:-1].encode(), json.dumps(text.strip())[1:-1].encode()))
    return values


def secret_variants(directory: Path, names: list[str]) -> list[bytes]:
    values = set()
    for name in names:
        path = directory / name
        if not path.is_file() or path.is_symlink() or path.stat().st_mode & 0o077:
            raise ValueError(f"Secret source is missing or not private: {name}")
        raw = path.read_bytes()
        if len(raw.strip()) < 16:
            raise ValueError(f"Secret source is unexpectedly short: {name}")
        values.update(encoded_secret_values(raw))
    return sorted(values, key=len, reverse=True)


def require_no_secrets(contents: bytes, values: list[bytes], phase: str) -> None:
    if any(value in contents for value in values):
        raise ValueError(f"Secret scan rejected {phase}; values were not printed")


def redact(contents: bytes, values: list[bytes]) -> bytes:
    for value in values:
        contents = contents.replace(value, b"[redacted]")
    return contents


def generated_secret_variants(runtime, identity: dict) -> list[bytes]:
    resources = json.loads(runtime.kubectl(identity, "get", "secrets", "-o", "json"))["items"]
    values = set()
    for resource in resources:
        labels = resource["metadata"].get("labels", {})
        if labels.get("strimzi.io/cluster") != "bank-kafka":
            continue
        for key, value in resource.get("data", {}).items():
            if key in {"password", "sasl.jaas.config"} or key.endswith((".key", ".p12", ".password")):
                raw = base64.b64decode(value)
                if len(raw.strip()) >= 16:
                    values.update(encoded_secret_values(raw))
    return sorted(values, key=len, reverse=True)


def run(arguments: list[str], *, environment: dict, values: list[bytes], log: Path,
        accepted: tuple[int, ...] = (0,)) -> bytes:
    result = subprocess.run(arguments, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            env=environment, timeout=1500)
    log.write_bytes(redact(result.stdout, values))
    log.chmod(0o600)
    if result.returncode not in accepted:
        raise RuntimeError(f"{arguments[0]} phase failed (exit {result.returncode}); inspect the redacted private log")
    require_no_secrets(result.stdout, values, log.name)
    return result.stdout


def validate_plan(plan: dict, *, operator_only: bool = False) -> dict:
    counts = {}
    for change in plan.get("resource_changes", []):
        address = change["address"]
        if address not in EXPECTED_RESOURCES:
            raise ValueError(f"Plan contains a foreign resource: {address}")
        if operator_only and address == "helm_release.bank":
            raise ValueError("Operator bootstrap plan must not include bank workloads")
        actions = change["change"]["actions"]
        if any(action not in {"create", "update", "read", "no-op"} for action in actions):
            raise ValueError(f"Destructive or replacement action is not allowed: {address}")
        label = "+".join(actions)
        counts[label] = counts.get(label, 0) + 1
    return counts


def main() -> None:
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("plan", "apply", "noop"))
    parser.add_argument("--project-dir", required=True, type=Path)
    parser.add_argument("--state-dir", required=True, type=Path)
    parser.add_argument("--secrets-dir", required=True, type=Path)
    parser.add_argument("--operator-only", action="store_true", help="Bootstrap CRDs/operator before bank images are available")
    args = parser.parse_args()
    project = args.project_dir.expanduser().resolve()
    if not (project / "kubernetes/runtime.py").is_file():
        raise ValueError("Source project does not contain the owned Kubernetes runtime")
    sys.path.insert(0, str(project / "kubernetes"))
    specification = importlib.util.spec_from_file_location("bank_kubernetes", project / "kubernetes/runtime.py")
    runtime = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(runtime)
    state_dir = runtime.require_external(args.state_dir, project.parents[1])
    identity = runtime.owned_identity(state_dir)
    secret_dir = runtime.require_private_source(args.secrets_dir)
    source = json.loads((state_dir / "source.json").read_text(encoding="utf-8"))
    preparation = importlib.import_module("prepare")
    if source["source_sha256"] != preparation.source_digest(project, source["analytics"], source["superset"]):
        raise ValueError("Public source assets changed; prepare fresh values before planning")
    if not args.operator_only and not source["images"]:
        raise ValueError("Load content-addressed Gradle application images before a real deployment plan")
    for name, expected in source["artifacts"].items():
        artifact = project / "build/deployment" / f"{name}.jar"
        if not artifact.is_file() or digest(artifact) != expected:
            raise ValueError(f"Packaged Gradle artifact changed: {name}.jar")
    values_path = state_dir / "public-values.yaml"
    public_values = yaml.safe_load(values_path.read_text(encoding="utf-8"))
    if public_values["global"]["environment"] != identity["namespace"].removeprefix("bank10-") or source["profile"] != identity["profile"]:
        raise ValueError("Prepared public values do not belong to the owned cluster")
    secret_values = sorted(set(secret_variants(secret_dir, source["secret_files"]) + generated_secret_variants(runtime, identity)), key=len, reverse=True)
    require_no_secrets(values_path.read_bytes(), secret_values, "public Helm values")
    environment = {key: value for key, value in os.environ.items()
                   if not key.startswith(("TF_VAR_", "TF_CLI_ARGS"))}
    environment.update(TF_DATA_DIR=str(state_dir / "tofu-data"), TF_IN_AUTOMATION="1")
    root = Path(__file__).resolve().parent
    base = ["tofu", f"-chdir={root}"]
    variables = {"environment": identity["namespace"].removeprefix("bank10-"), "profile": identity["profile"],
                 "kubeconfig_path": identity["kubeconfig"], "kube_context": identity["context"],
                 "project_dir": str(project), "public_values_path": str(values_path),
                 "analytics_enabled": source["analytics"], "superset_enabled": source["superset"]}
    variable_path = state_dir / "deployment.tfvars.json"
    variable_path.write_text(json.dumps(variables, indent=2) + "\n", encoding="utf-8")
    variable_path.chmod(0o600)
    run([*base, "init", "-input=false", "-reconfigure", f"-backend-config=path={state_dir / 'terraform.tfstate'}"],
        environment=environment, values=secret_values, log=state_dir / "tofu-init.log")
    component = "operator" if args.operator_only else "bank"
    plan_path = state_dir / (f"reviewed-{component}.tfplan" if args.operation != "noop" else f"noop-{component}.tfplan")
    receipt_path = state_dir / f"plan-receipt-{component}.json"
    current = {"component": component, "cluster_uid": identity["cluster_uid"], "chart_sha256": preparation.chart_digest(project),
               "infrastructure_sha256": infrastructure_digest(root),
               "public_values_sha256": digest(values_path), "source_sha256": digest(state_dir / "source.json"),
               "variables_sha256": digest(variable_path)}
    if args.operation == "apply":
        if not receipt_path.is_file() or not plan_path.is_file():
            raise ValueError("A reviewed saved plan and receipt are required before apply")
        receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
        if receipt["inputs"] != current or receipt["plan_sha256"] != digest(plan_path):
            raise ValueError("Reviewed plan inputs changed; prepare and review a fresh plan")
    else:
        targets = ["-target=helm_release.strimzi"] if args.operator_only else []
        run([*base, "plan", "-input=false", "-no-color", *targets, f"-var-file={variable_path}", f"-out={plan_path}"],
            environment=environment, values=secret_values, log=state_dir / "tofu-plan.log")
    plan_json = run([*base, "show", "-json", str(plan_path)], environment=environment,
                    values=secret_values, log=state_dir / "tofu-plan.json")
    counts = validate_plan(json.loads(plan_json), operator_only=args.operator_only)
    if args.operation == "noop" and any(key not in {"no-op", "read"} for key in counts):
        raise ValueError(f"Repeated plan is not empty: {counts}")
    if args.operation == "plan":
        receipt_path.write_text(json.dumps({"inputs": current, "plan_sha256": digest(plan_path), "actions": counts}, indent=2) + "\n", encoding="utf-8")
        receipt_path.chmod(0o600)
    elif args.operation == "apply":
        run([*base, "apply", "-input=false", "-no-color", str(plan_path)], environment=environment,
            values=secret_values, log=state_dir / "tofu-apply.log")
    for path in state_dir.glob("*.tfstate*"):
        if path.is_file():
            require_no_secrets(path.read_bytes(), secret_values, path.name)
            path.chmod(0o600)
    print(f"{args.operation}: bank-only Helm actions {counts}; exact secret-value scan passed")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError, yaml.YAMLError) as error:
        print(f"FAIL {error}", file=sys.stderr)
        sys.exit(1)
