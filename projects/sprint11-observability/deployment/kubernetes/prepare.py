"""Prepare public Helm values from the authoritative project files."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys

import yaml


APPS = ("accounts", "cash", "transfer", "notifications", "gateway", "front", "analytics")
PUBLIC_ASSETS = {
    "bank11-setup": {
        "start-database.sh": "deployment/scripts/start-database.sh",
        "init-config.sh": "deployment/discovery/init-config.sh",
    },
    "bank11-database": {"init-database.sh": "deployment/database/init-database.sh"},
    "bank11-authorization": {
        "start-authorization.sh": "deployment/authorization/start-authorization.sh",
        "bank-realm.json": "deployment/authorization/bank-realm.json",
    },
    "bank11-edge": {"Caddyfile": "deployment/edge/Caddyfile"},
}
for component in ("zipkin", "prometheus", "grafana", "elk"):
    PUBLIC_ASSETS[f"bank11-{component}"] = {
        path.name: str(path.relative_to(Path(__file__).resolve().parents[2]))
        for path in sorted((Path(__file__).resolve().parents[2] / component).iterdir())
        if path.is_file() and not path.is_symlink()
    }
ANALYTICS_ASSETS = {
    "bank11-analytics-setup": {
        "start-cassandra.sh": "deployment/scripts/start-cassandra.sh",
        "probe-cassandra.py": "deployment/scripts/probe-cassandra.py",
        "init-cassandra.sh": "deployment/scripts/init-cassandra.sh",
        "start-clickhouse.sh": "deployment/scripts/start-clickhouse.sh",
        "init-clickhouse.sh": "deployment/scripts/init-clickhouse.sh",
        "clickhouse-users.xml": "deployment/database/clickhouse-users.xml",
        "clickhouse-storage.xml": "deployment/database/clickhouse-storage.xml",
        "init-clickhouse.sql": "deployment/database/init-clickhouse.sql",
        "activity-marts.sql": "services/analytics/src/main/resources/clickhouse/activity-marts.sql",
    },
}


def project_dir() -> Path:
    return Path(__file__).resolve().parents[2]


def require_external(directory: Path, repository: Path) -> Path:
    if directory.is_symlink():
        raise ValueError("Output directory must not be a symlink")
    directory = directory.expanduser().absolute()
    if directory.is_relative_to(repository.resolve()):
        raise ValueError("Generated runtime files must remain outside the repository")
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    directory = directory.resolve()
    if directory.is_relative_to(repository.resolve()):
        raise ValueError("Output directory resolves inside the repository")
    directory.chmod(0o700)
    return directory


def secret_bindings(project: Path, analytics: bool, superset: bool, *, telegram: bool = True) -> dict:
    bindings = json.loads((project / "deployment/kubernetes/secret-bindings.json").read_text(encoding="utf-8"))
    selected = {"database", "authorization", "edge", *APPS[:-1],
                "prometheus", "grafana", "elasticsearch", "logstash", "kibana"}
    if telegram:
        selected.add("alertmanager")
    selected.update(f"kafka-{app}" for app in ("accounts", "cash", "transfer", "notifications"))
    if analytics:
        selected.update({"analytics", "cassandra", "clickhouse", "kafka-analytics"})
    if superset:
        selected.update({"superset", "bank-bi"})
    return {f"bank11-{name}": items for name, items in bindings.items() if name in selected}


def image_reference(project: Path, name: str) -> str:
    hashed = hashlib.sha256()
    if name in APPS or name == "bank-bi":
        artifact = project / "build/deployment" / f"{name}.jar"
        if not artifact.is_file():
            raise ValueError(f"Packaged Gradle artifact missing: {name}.jar")
        hashed.update(artifact.read_bytes() + (project / "Dockerfile").read_bytes()
                      + (project / "config/jvm-network.security").read_bytes()
                      + (project / "config/entrypoint.sh").read_bytes())
    else:
        context = project / "deployment" / name
        for path in sorted(context.rglob("*")):
            if path.is_file() and "__pycache__" not in path.parts:
                hashed.update(str(path.relative_to(context)).encode() + b"\0" + path.read_bytes())
    return f"bank11-{name}:{hashed.hexdigest()[:20]}"


def chart_digest(project: Path) -> str:
    hashed = hashlib.sha256()
    charts = project / "deployment/charts"
    for path in sorted(charts.rglob("*")):
        if path.is_file():
            # A source-only directory move must not roll every application Pod.
            logical_path = Path("charts") / path.relative_to(charts)
            hashed.update(str(logical_path).encode() + b"\0" + path.read_bytes())
    return hashed.hexdigest()


def source_digest(project: Path, analytics: bool, superset: bool = False) -> str:
    files = {project / relative for sources in (PUBLIC_ASSETS | (ANALYTICS_ASSETS if analytics else {})).values()
             for relative in sources.values()}
    files.update(path for directory in ("deployment/charts", "deployment/kubernetes", "openbao", "zipkin", "prometheus", "grafana", "elk") for path in (project / directory).rglob("*")
                 if path.is_file() and "__pycache__" not in path.parts)
    files.update(project / name for name in ("Dockerfile", "config/jvm-network.security", "deployment/authorization/Dockerfile", "deployment/edge/Dockerfile"))
    if superset:
        files.update(path for path in (project / "deployment/superset").rglob("*") if path.is_file() and "__pycache__" not in path.parts)
    hashed = hashlib.sha256()
    for path in sorted(files):
        hashed.update(str(path.relative_to(project)).encode() + b"\0" + path.read_bytes())
    return hashed.hexdigest()


def prepare(output: Path, *, environment: str, profile: str, analytics: bool, superset: bool,
            external_port: int, images: dict | None = None, telegram: bool = False) -> dict:
    project = project_dir()
    output = require_external(output, project.parents[1])
    assets = {}
    for config_map, sources in (PUBLIC_ASSETS | (ANALYTICS_ASSETS if analytics else {})).items():
        assets[config_map] = {}
        for key, relative in sources.items():
            path = project / relative
            if not path.is_file() or path.is_symlink():
                raise ValueError(f"Public source asset must be a regular file: {relative}")
            contents = path.read_text(encoding="utf-8")
            if key in {"Caddyfile", "bank-realm.json"}:
                contents = contents.replace(":8460", f":{external_port}")
                contents = contents.replace("X-Forwarded-Port 8460", f"X-Forwarded-Port {external_port}")
            assets[config_map][key] = contents
    values = {
        "global": {"environment": environment, "externalPort": external_port, "analyticsEnabled": analytics,
                   "chartRevision": chart_digest(project),
                   "observability": {"alerting": {"telegram": {"enabled": telegram}}}},
        "kafka": {"profile": profile, "analyticsEnabled": analytics},
        "superset": {"enabled": superset},
        "assets": assets,
        "openbao": {"bindings": secret_bindings(project, analytics, superset, telegram=telegram)},
    }
    for component in ("zipkin", "prometheus", "grafana", "elk"):
        values[component] = {"assets": assets.pop(f"bank11-{component}")}
    # Public deployment flags keep scrape targets aligned with the selected applications.
    applications = yaml.safe_load((project / "deployment/charts/bank/values.yaml").read_text(encoding="utf-8"))["apps"]
    values["prometheus"]["apps"] = {name: {key: app[key] for key in ("web", "optional") if key in app}
                                  for name, app in applications.items()}
    if images:
        expected = {*APPS[:-1], "authorization", "edge"}
        if analytics:
            expected.add("analytics")
        if superset:
            expected.update({"superset", "bank-bi"})
        if set(images) != expected or any(not re.fullmatch(rf"bank11-{re.escape(name)}:[a-f0-9]{{20}}", image)
                                          for name, image in images.items()):
            raise ValueError("Image manifest must contain every selected content-addressed application image")
        if any(image != image_reference(project, name) for name, image in images.items()):
            raise ValueError("Image manifest does not match the current packaged Gradle artifacts/build inputs")
        values["apps"] = {name: {"image": images[name]} for name in APPS if name in images}
        for name in ("edge", "authorization"):
            if name in images:
                values[name] = {"image": images[name]}
        if "superset" in images:
            values["superset"]["image"] = images["superset"]
        if "bank-bi" in images:
            values["superset"]["provisionerImage"] = images["bank-bi"]
    bindings = secret_bindings(project, analytics, superset, telegram=telegram)
    values_path = output / "public-values.yaml"
    values_path.write_text(yaml.safe_dump(values, sort_keys=True), encoding="utf-8")
    values_path.chmod(0o600)
    bindings_path = output / "secret-bindings.json"
    bindings_path.write_text(json.dumps(bindings, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    bindings_path.chmod(0o600)
    artifacts = {name: hashlib.sha256((project / "build/deployment" / f"{name}.jar").read_bytes()).hexdigest()
                 for name in (APPS + ("bank-bi",)) if images and name in images}
    receipt = {"source_sha256": source_digest(project, analytics, superset), "namespace": f"bank11-{environment}",
               "profile": profile, "analytics": analytics, "superset": superset, "telegram": telegram,
               "images": images or {}, "artifacts": artifacts,
               "secret_files": sorted({name for items in bindings.values() for name in items.values()})}
    (output / "source.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print(f"Prepared public values for bank11-{environment}/{profile}; secret values were not read")
    return receipt


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--environment", choices=("dev", "test", "prod"), default="dev")
    parser.add_argument("--profile", choices=("single", "ha"), default="single")
    parser.add_argument("--analytics", action="store_true")
    parser.add_argument("--telegram", action="store_true", help="Enable the separately supplied Telegram destination")
    parser.add_argument("--superset", action="store_true")
    parser.add_argument("--external-port", type=int, default=8460)
    parser.add_argument("--images", type=Path)
    args = parser.parse_args()
    if not 1024 < args.external_port < 65536:
        parser.error("External TLS port must be between 1025 and 65535")
    images = json.loads(args.images.read_text(encoding="utf-8")) if args.images else None
    prepare(args.output_dir, environment=args.environment, profile=args.profile,
            analytics=args.analytics or args.superset, superset=args.superset,
            external_port=args.external_port, images=images, telegram=args.telegram)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, yaml.YAMLError) as error:
        print(f"FAIL {error}", file=sys.stderr)
        sys.exit(1)
