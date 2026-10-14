#!/usr/bin/env python3
"""Изолированный ClickHouse: два шарда, две реплики, реальный Java-клиент и отказ узла."""

import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import uuid

PROJECT = Path(__file__).resolve().parents[1]
SERVICES = ["keeper", "ch-a1", "ch-a2", "ch-b1", "ch-b2"]
PORTS = range(18131, 18135)


def main():
    name = "storefront-cluster-test-" + uuid.uuid4().hex[:12]
    with tempfile.TemporaryDirectory(prefix="storefront-cluster-secrets-") as directory:
        spec = importlib.util.spec_from_file_location("cluster_secrets", PROJECT / "scripts/init-secrets.py")
        secrets = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(secrets)
        secrets.initialize(directory, PROJECT)
        environment = {**os.environ, "STORE_SECRETS_DIR": directory,
                       "STORE_TEST_CLUSTER_URLS": ",".join(f"http://127.0.0.1:{port}" for port in PORTS),
                       "STORE_TEST_CLUSTER_CONTAINERS": ",".join(f"{name}-{node}-1" for node in SERVICES[1:])}
        for role in ("admin", "writer", "reader"):
            environment[f"STORE_TEST_ANALYTICS_{role.upper()}_PASSWORD"] = (
                Path(directory) / f"analytics_{role}_password").read_text().strip()
        command = ["docker", "compose", "-p", name, "-f", "compose.yaml", "-f", "compose.cluster.yaml"]
        try:
            subprocess.run([*command, "up", "-d", "--wait", "--wait-timeout", "90", *SERVICES],
                           cwd=PROJECT, env=environment, check=True)
            subprocess.run([str(PROJECT / "gradlew"), "--no-daemon", "--console=plain", "clusterTest"],
                           cwd=PROJECT, env=environment, check=True)
            print("ClickHouse cluster: user sharding, replication, FINAL, quorum and coordinator failover passed")
        finally:
            # Имя генерируется этой проверкой; ни рабочие тома магазина, ни чужие Compose-проекты не затрагиваются.
            subprocess.run([*command, "down", "--volumes"], cwd=PROJECT, env=environment, check=True)


if __name__ == "__main__":
    main()
