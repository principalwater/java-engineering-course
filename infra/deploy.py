#!/usr/bin/env python3
"""Run one local stack with external state and an explicitly reviewed saved plan."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import subprocess
import sys

INFRA = Path(__file__).resolve().parent
REPOSITORY = INFRA.parent
# Multi-service Gradle projects share executable hashes and the documentation exclusion below.
JAR_PREFIXES = ("sprint07-", "sprint08-", "sprint09")
SECRET_NAME = re.compile(r"(PASSWORD|SECRET|TOKEN)$")
MIN_SECRET_LENGTH = 16
STACKS = {
    "sprint03": "projects/sprint03-spring-blog",
    "sprint04": "projects/sprint04-spring-boot-blog",
    "sprint05-postgresql": "projects/sprint05-storefront",
    "sprint05-h2": "projects/sprint05-storefront",
    "sprint06-postgresql": "projects/sprint06-reactive-storefront",
    "sprint06-h2": "projects/sprint06-reactive-storefront",
    "sprint07-postgresql": "projects/sprint07-rest-service",
    "sprint07-h2": "projects/sprint07-rest-service",
    "sprint08-postgresql": "projects/sprint08-security",
    "sprint08-h2": "projects/sprint08-security",
    "sprint09": "projects/sprint09-bank",
    "docker-exercise": "exercises/supplements/docker",
    "mvc-exercise": "exercises/sprint03/spring-mvc-jdbc",
}


def external_directory(path):
    path = Path(path).expanduser()
    if path.is_symlink() or path.resolve().is_relative_to(REPOSITORY):
        raise ValueError("Deployment state and secrets must remain outside the repository")
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    path.chmod(0o700)
    return path.resolve()


def project_directory(stack, environment=None):
    selected = (environment or {}).get("TF_VAR_bank_project_directory") if stack == "sprint09" else None
    return Path(selected).resolve() if selected else REPOSITORY / STACKS[stack]


def fingerprint(stack, environment):
    files = list(INFRA.rglob("*.tf")) + list(INFRA.rglob("*.conf")) + [INFRA / ".terraform.lock.hcl", INFRA / "deploy.py", INFRA / "requirements.txt"]
    project = project_directory(stack, environment)
    files += list(project.rglob("*"))
    if stack == "sprint09":
        files += list(Path(environment["TF_VAR_bank_runtime_directory"]).glob("*"))
    digest = hashlib.sha256(json.dumps(environment, sort_keys=True).encode())
    for path in sorted(files):
        if path.is_file() and not any(part in {"target", "build", ".gradle", "__pycache__", "node_modules"} for part in path.parts):
            # Human documentation is independent of executable inputs; packaged resources stay covered.
            if stack.startswith(JAR_PREFIXES) and path.is_relative_to(project) and path.relative_to(project).parts[0] in {
                "README.md", "docs", "screenshots",
            }:
                continue
            identity = "project/" + str(path.relative_to(project)) if path.is_relative_to(project) else str(path)
            digest.update(identity.encode())
            digest.update(path.read_bytes())
    for name in ["target/docker-study.jar", "target/spring-web-demo.war"]:
        artifact = REPOSITORY / STACKS[stack] / name
        if artifact.is_file():
            digest.update(artifact.read_bytes())
    return digest.hexdigest()


def artifact_hashes(stack, environment=None):
    if not stack.startswith(JAR_PREFIXES):
        return {}
    result = {}
    services = ["accounts", "cash", "transfer", "notifications", "gateway", "front", "analytics"] if stack == "sprint09" else ["storefront", "payments"]
    for service in services:
        path = project_directory(stack, environment) / "build/deployment" / (service + ".jar")
        digest = hashlib.sha256()
        with path.open("rb") as artifact:
            for block in iter(lambda: artifact.read(1024 * 1024), b""):
                digest.update(block)
        result[service] = digest.hexdigest()
    return result


def secret_values(directory):
    """Contents of external secret files long enough to be unique; they must never appear in a plan or state."""
    directory = Path(directory)
    values = set()
    for path in directory.iterdir() if directory.is_dir() else []:
        if path.is_file() and not path.is_symlink():
            value = path.read_text(errors="replace").strip()
            if len(value) >= MIN_SECRET_LENGTH:
                values.add(value)
    return values


def check_secret_values(document, known_secrets):
    serialized = json.dumps(document)
    if any(value in serialized or json.dumps(value)[1:-1] in serialized for value in known_secrets):
        raise ValueError("A secret file value must not appear in public configuration, plan or state")


def check_plan(plan, stack, known_secrets=()):
    """Reject secret-bearing settings and changes outside this stack's ownership."""
    prefix = f"java-tofu-{stack}"
    check_secret_values(plan, known_secrets)
    for change in plan.get("resource_changes", []):
        kind = change["type"]
        value = change["change"].get("after") or {}
        if kind == "docker_volume" and "delete" in change["change"]["actions"]:
            raise ValueError("A deployment plan must not delete persistent volumes")
        if kind in {"docker_container", "docker_network", "docker_volume"}:
            for current in [change["change"].get("before"), value]:
                if current and current.get("name") != prefix and not current.get("name", "").startswith(prefix + "-"):
                    raise ValueError("A plan attempted to manage a resource outside this stack")
        if kind != "docker_container":
            continue
        if value.get("privileged") or any(port.get("ip") != "127.0.0.1" for port in value.get("ports") or []):
            raise ValueError("Containers must be unprivileged with loopback-only published ports")
        if any(SECRET_NAME.search(item.partition("=")[0]) for item in value.get("env") or []):
            raise ValueError("Secret values must not be stored in container configuration or state")
        for mount in value.get("volumes") or []:
            if mount.get("container_path", "").startswith("/run/secrets/"):
                path = Path(mount["host_path"])
                if not mount.get("read_only") or path.resolve().is_relative_to(REPOSITORY):
                    raise ValueError("Secret mounts must be read-only files outside the repository")


def prepare_bank_configuration(project, runtime, known_secrets=()):
    import yaml

    runtime.mkdir(parents=True, exist_ok=True, mode=0o700)
    for source, target in [("compose.yaml", "compose.json"), ("compose.analytics.yaml", "compose.analytics.json")]:
        try:
            configuration = yaml.safe_load((project / source).read_text())
        except yaml.YAMLError as error:
            raise ValueError("Bank deployment declarations must contain safe YAML") from error
        if not isinstance(configuration, dict) or not isinstance(configuration.get("services"), dict):
            raise ValueError("Bank deployment declarations require a service mapping")
        check_secret_values(configuration, known_secrets)
        if any(SECRET_NAME.search(key) for service in configuration["services"].values() for key in service.get("environment", {})):
            raise ValueError("Bank public configuration must use external file-secret paths")
        (runtime / target).write_text(json.dumps(configuration, sort_keys=True))
    for source, target in [("edge/Caddyfile", "Caddyfile"), ("authorization/bank-realm.json", "bank-realm.json")]:
        template = (project / source).read_text()
        check_secret_values(template, known_secrets)
        (runtime / target).write_text(template.replace(":8449", ":28450").replace("X-Forwarded-Port 8449", "X-Forwarded-Port 28450"))


def prepare(stack, secrets_directory, environment):
    project = project_directory(stack, environment)
    if not project.is_dir():
        raise ValueError(f"Project sources for {stack} are unavailable in this checkout: {project}")
    if stack == "sprint09":
        selected = {**environment, "BANK_SECRETS_DIR": str(secrets_directory)}
        subprocess.run([sys.executable, "scripts/init-secrets.py"], cwd=project, env=selected, check=True)
        subprocess.run([sys.executable, "scripts/init-tls.py"], cwd=project, env=selected, check=True)
        subprocess.run([str(project / "gradlew"), "--no-daemon", "--console=plain", "check", "packageDeployment"], cwd=project, env=selected, check=True)
        prepare_bank_configuration(project, Path(environment["TF_VAR_bank_runtime_directory"]), secret_values(secrets_directory))
    elif stack.startswith("sprint"):
        secret_variable = "STORE_SECRETS_DIR" if stack.startswith(("sprint05", "sprint06", "sprint07", "sprint08")) else "BLOG_SECRETS_DIR"
        subprocess.run([sys.executable, "scripts/init-secrets.py"], cwd=project,
                       env={**environment, secret_variable: str(secrets_directory)}, check=True)
        if stack.startswith("sprint08-"):
            subprocess.run([sys.executable, "scripts/init-tls.py"], cwd=project,
                           env={**environment, secret_variable: str(secrets_directory)}, check=True)
        if stack.startswith(JAR_PREFIXES):
            subprocess.run([sys.executable, "scripts/package.py"],
                           cwd=project, env=environment, check=True)
    elif stack == "docker-exercise":
        destination = secrets_directory / "db_password"
        try:
            descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        except FileExistsError:
            if destination.is_symlink() or not destination.is_file() or not destination.read_bytes().strip():
                raise ValueError("Expected a nonempty regular external password file")
            destination.chmod(0o600)
        else:
            with os.fdopen(descriptor, "w") as stream:
                stream.write(secrets.token_hex(32) + "\n")
    if stack in {"docker-exercise", "mvc-exercise"}:
        wrapper = REPOSITORY / "exercises/sprint01/junit5/mvnw"
        # Pin ZIP timestamps so an unchanged WAR/JAR does not cause deployment churn.
        subprocess.run([str(wrapper), "-B", "--no-transfer-progress", "-Dproject.build.outputTimestamp=2026-01-01T00:00:00Z",
                        "-f", str(project / "pom.xml"), "verify"],
                       env=environment, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stack", choices=STACKS)
    parser.add_argument("action", choices=["plan", "apply", "status"])
    parser.add_argument("--analytics", action="store_true", help="Enable the selected project's optional analytics")
    parser.add_argument("--project-dir", type=Path, help="Explicit Sprint 9 source directory from a separate checkout")
    parser.add_argument("--replace", action="append", default=[], help="Plan replacement of an exact resource address")
    args = parser.parse_args()
    if args.project_dir and args.stack != "sprint09":
        parser.error("--project-dir is supported only for the separately checked-out bank project")
    if args.analytics and not args.stack.startswith("sprint"):
        parser.error("Analytics is available only for project deployments")
    if args.replace and args.action != "plan":
        parser.error("--replace is valid only when preparing a plan")

    state_path = Path(os.environ.get("COURSE_DEPLOY_STATE", Path.home() / ".local/state/java-engineering-course")) / args.stack
    secrets_path = Path(os.environ.get("COURSE_DEPLOY_SECRETS", Path.home() / ".config/java-engineering-course/tofu")) / args.stack
    source = (args.project_dir or REPOSITORY / STACKS[args.stack]).expanduser().resolve()
    if args.stack == "sprint09" and any(path.expanduser().resolve().is_relative_to(source) for path in [state_path, secrets_path]):
        raise ValueError("Bank state and secrets must remain outside its source checkout")
    state = external_directory(state_path)
    secrets_directory = external_directory(secrets_path)
    docker_host = subprocess.check_output(["docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}"], text=True).strip()
    variables = {
        "TF_VAR_deployment": args.stack,
        "TF_VAR_docker_host": docker_host,
        "TF_VAR_runtime_user": f"{os.getuid()}:{os.getgid()}",
        "TF_VAR_secrets_directory": str(secrets_directory),
        "TF_VAR_analytics_enabled": str(args.analytics).lower(),
    }
    if args.stack == "sprint09":
        variables.update({"TF_VAR_bank_project_directory": str(source), "TF_VAR_bank_runtime_directory": str(state / "runtime")})
    environment = {**os.environ, **variables, "TF_DATA_DIR": str(state / "providers")}
    environment.pop("DOCKER_CONTEXT", None)
    environment["DOCKER_HOST"] = docker_host
    tool = os.environ.get("TF_CLI", "tofu")
    command = [tool, f"-chdir={INFRA}"]
    plan = state / "reviewed.tfplan"
    receipt = state / "plan-inputs.json"
    if args.action == "plan":
        prepare(args.stack, secrets_directory, environment)
    if args.stack.startswith(JAR_PREFIXES) and args.action != "status":
        variables["TF_VAR_deployment_artifact_hashes"] = json.dumps(artifact_hashes(args.stack, variables), sort_keys=True)
        environment.update(variables)
    subprocess.run(command + ["init", "-input=false", "-reconfigure", f"-backend-config=path={state / 'terraform.tfstate'}"], env=environment, check=True)
    if args.action == "plan":
        subprocess.run(command + ["validate"], env=environment, check=True)
        receipt.unlink(missing_ok=True)
        subprocess.run(command + ["plan", "-input=false", f"-out={plan}"] + [f"-replace={address}" for address in args.replace], env=environment, check=True)
        check_plan(json.loads(subprocess.check_output(command + ["show", "-json", str(plan)], env=environment)), args.stack,
                   secret_values(secrets_directory))
        receipt.write_text(json.dumps({"fingerprint": fingerprint(args.stack, variables), "variables": variables}))
        plan.chmod(0o600)
        receipt.chmod(0o600)
        print(f"Review the plan above, then run: python3 infra/deploy.py {args.stack} apply" +
              (" --analytics" if args.analytics else "") + (f" --project-dir {shlex.quote(str(args.project_dir))}" if args.project_dir else ""))
    elif args.action == "apply":
        if not receipt.exists() or not plan.exists():
            raise ValueError("Prepare and review a plan before applying")
        approved = json.loads(receipt.read_text())
        if approved != {"fingerprint": fingerprint(args.stack, variables), "variables": variables}:
            raise ValueError("Deployment inputs changed after planning; prepare a new plan")
        check_plan(json.loads(subprocess.check_output(command + ["show", "-json", str(plan)], env=environment)), args.stack,
                   secret_values(secrets_directory))
        try:
            subprocess.run(command + ["apply", "-input=false", str(plan)], env=environment, check=True)
        finally:
            for name in ["terraform.tfstate", "terraform.tfstate.backup"]:
                state_file = state / name
                if state_file.is_file():
                    state_file.chmod(0o600)
        receipt.unlink()
        plan.unlink()
    else:
        subprocess.run(command + ["output", "-json", "deployment"], env=environment, check=True)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        sys.exit(f"Deployment failed: {error}")
