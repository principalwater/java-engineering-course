#!/usr/bin/env python3
"""Run one local stack with external state and an explicitly reviewed saved plan."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys

INFRA = Path(__file__).resolve().parent
REPOSITORY = INFRA.parent
# Two-JAR projects share Gradle packaging, executable hashes and the documentation exclusion below.
JAR_PREFIXES = ("sprint07-", "sprint08-")
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


def fingerprint(stack, environment):
    files = list(INFRA.rglob("*.tf")) + list(INFRA.rglob("*.conf")) + [INFRA / ".terraform.lock.hcl", INFRA / "deploy.py"]
    project = REPOSITORY / STACKS[stack]
    files += list(project.rglob("*"))
    digest = hashlib.sha256(json.dumps(environment, sort_keys=True).encode())
    for path in sorted(files):
        if path.is_file() and not any(part in {"target", "build", ".gradle", "__pycache__"} for part in path.parts):
            # Human documentation is independent of executable inputs; packaged resources stay covered.
            if stack.startswith(JAR_PREFIXES) and path.is_relative_to(project) and path.relative_to(project).parts[0] in {
                "README.md", "docs", "screenshots",
            }:
                continue
            digest.update(str(path.relative_to(REPOSITORY)).encode())
            digest.update(path.read_bytes())
    for name in ["target/docker-study.jar", "target/spring-web-demo.war"]:
        artifact = REPOSITORY / STACKS[stack] / name
        if artifact.is_file():
            digest.update(artifact.read_bytes())
    return digest.hexdigest()


def artifact_hashes(stack):
    if not stack.startswith(JAR_PREFIXES):
        return {}
    result = {}
    for service in ["storefront", "payments"]:
        path = REPOSITORY / STACKS[stack] / "build/deployment" / (service + ".jar")
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


def check_plan(plan, stack, known_secrets=()):
    """Reject secret-bearing settings and changes outside this stack's ownership."""
    prefix = f"java-tofu-{stack}"
    serialized = json.dumps(plan)
    if any(value in serialized for value in known_secrets):
        raise ValueError("A secret file value must not appear in the plan or state")
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


def prepare(stack, secrets_directory, environment):
    project = REPOSITORY / STACKS[stack]
    if not project.is_dir():
        raise ValueError(f"Project sources for {stack} are unavailable in this checkout: {project}")
    if stack.startswith("sprint"):
        secret_variable = "STORE_SECRETS_DIR" if stack.startswith(("sprint05", "sprint06", "sprint07", "sprint08")) else "BLOG_SECRETS_DIR"
        subprocess.run([sys.executable, "scripts/init-secrets.py"], cwd=project,
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
    parser.add_argument("--replace", action="append", default=[], help="Plan replacement of an exact resource address")
    args = parser.parse_args()
    if args.analytics and not args.stack.startswith("sprint"):
        parser.error("Analytics is available only for project deployments")
    if args.replace and args.action != "plan":
        parser.error("--replace is valid only when preparing a plan")

    state = external_directory(Path(os.environ.get("COURSE_DEPLOY_STATE", Path.home() / ".local/state/java-engineering-course")) / args.stack)
    secrets_directory = external_directory(Path(os.environ.get("COURSE_DEPLOY_SECRETS", Path.home() / ".config/java-engineering-course/tofu")) / args.stack)
    docker_host = subprocess.check_output(["docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}"], text=True).strip()
    variables = {
        "TF_VAR_deployment": args.stack,
        "TF_VAR_docker_host": docker_host,
        "TF_VAR_runtime_user": f"{os.getuid()}:{os.getgid()}",
        "TF_VAR_secrets_directory": str(secrets_directory),
        "TF_VAR_analytics_enabled": str(args.analytics).lower(),
    }
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
        variables["TF_VAR_deployment_artifact_hashes"] = json.dumps(artifact_hashes(args.stack), sort_keys=True)
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
              (" --analytics" if args.analytics else ""))
    elif args.action == "apply":
        if not receipt.exists() or not plan.exists():
            raise ValueError("Prepare and review a plan before applying")
        approved = json.loads(receipt.read_text())
        if approved != {"fingerprint": fingerprint(args.stack, variables), "variables": variables}:
            raise ValueError("Deployment inputs changed after planning; prepare a new plan")
        check_plan(json.loads(subprocess.check_output(command + ["show", "-json", str(plan)], env=environment)), args.stack,
                   secret_values(secrets_directory))
        subprocess.run(command + ["apply", "-input=false", str(plan)], env=environment, check=True)
        receipt.unlink()
        plan.unlink()
    else:
        subprocess.run(command + ["output", "-json", "deployment"], env=environment, check=True)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        sys.exit(f"Deployment failed: {error}")
