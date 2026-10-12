#!/usr/bin/env python3
"""Проверяет ClickHouse на отдельном сервере и наборе данных, не меняя работающий магазин."""

import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
from urllib.error import URLError
from urllib.request import Request, urlopen
import uuid

PROJECT = Path(__file__).resolve().parents[1]
# Автоматически назначенный published port меняется при stop/start; проверке восстановления нужен стабильный.
TEST_ANALYTICS_PORT = 18125


def main():
    configuration = json.loads(subprocess.check_output([
        "docker", "compose", "-f", "compose.yaml", "-f", "compose.analytics.yaml", "config", "--format", "json"], cwd=PROJECT))
    image = configuration["services"]["clickhouse"]["image"]
    container = "storefront-analytics-test-" + uuid.uuid4().hex
    with tempfile.TemporaryDirectory(prefix="storefront-analytics-secrets-") as directory:
        passwords = {}
        mounts = []
        for role in ("admin", "writer", "reader"):
            passwords[role] = secrets.token_hex(32)
            path = Path(directory) / f"analytics_{role}_password"
            descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "w") as stream:
                stream.write(passwords[role] + "\n")
            mounts += ["--mount", f"type=bind,source={path},target=/run/secrets/{path.name},readonly"]
        for source, target in {
            "analytics/users.xml": "/etc/clickhouse-server/users.d/storefront.xml",
            "analytics/001-order-lines.sql": "/docker-entrypoint-initdb.d/001-order-lines.sql",
            "analytics/start-clickhouse.sh": "/setup/start-clickhouse.sh",
        }.items():
            mounts += ["--mount", f"type=bind,source={PROJECT / source},target={target},readonly"]
        created = False
        try:
            subprocess.run(["docker", "run", "-d", "--name", container, "--entrypoint", "sh",
                            "-p", f"127.0.0.1:{TEST_ANALYTICS_PORT}:8123", "-e", "CLICKHOUSE_USER=storefront_admin",
                            "-e", "CLICKHOUSE_PASSWORD_FILE=/run/secrets/analytics_admin_password",
                            "-e", "CLICKHOUSE_DB=storefront", "-e", "CLICKHOUSE_ALWAYS_RUN_INITDB_SCRIPTS=1",
                            *mounts, image, "/setup/start-clickhouse.sh"], check=True, stdout=subprocess.DEVNULL)
            created = True
            port = subprocess.check_output(["docker", "port", container, "8123"], text=True).strip().rsplit(":", 1)[1]
            endpoint = f"http://127.0.0.1:{port}"
            deadline = time.monotonic() + 60
            while True:
                try:
                    query = Request(endpoint, data=b"SYSTEM STOP MERGES storefront.order_lines",
                                    headers={"X-ClickHouse-User": "storefront_admin", "X-ClickHouse-Key": passwords["admin"]})
                    with urlopen(query, timeout=5) as response:
                        assert response.status == 200
                    break
                # Опубликованный Docker-порт может сбросить TCP до готовности ClickHouse.
                except (URLError, TimeoutError, ConnectionResetError):
                    if time.monotonic() >= deadline:
                        raise
                    time.sleep(1)
            environment = {**os.environ, "STORE_TEST_ANALYTICS_URL": endpoint,
                           "STORE_TEST_ANALYTICS_WRITER_PASSWORD": passwords["writer"],
                           "STORE_TEST_ANALYTICS_READER_PASSWORD": passwords["reader"],
                           "STORE_TEST_CLICKHOUSE_CONTAINER": container}
            subprocess.run([str(PROJECT / "gradlew"), "--no-daemon", "--console=plain", "analyticsTest"],
                           cwd=PROJECT, env=environment, check=True)
            print("Isolated ClickHouse: duplicate delivery, durable queue and outage recovery passed")
        finally:
            if created:
                subprocess.run(["docker", "rm", "-f", "-v", container], check=True, stdout=subprocess.DEVNULL)


if __name__ == "__main__":
    main()
