#!/usr/bin/env python3
"""Проверяет JPA/транзакции на отдельной БД; данные работающего магазина не затрагиваются."""

import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
import uuid

PROJECT = Path(__file__).resolve().parents[1]


def main():
    configuration = json.loads(subprocess.check_output(
        ["docker", "compose", "-f", "compose.yaml", "config", "--format", "json"], cwd=PROJECT))
    image = configuration["services"]["db"]["image"]
    name = "storefront-test-" + uuid.uuid4().hex
    with tempfile.TemporaryDirectory(prefix="storefront-test-secrets-") as directory:
        secret = Path(directory) / "db_password"
        password = secrets.token_hex(32)
        descriptor = os.open(secret, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w") as stream:
            stream.write(password + "\n")
        created = False
        try:
            subprocess.run(["docker", "run", "-d", "--rm", "--name", name,
                            "-e", "POSTGRES_DB=store_tests", "-e", "POSTGRES_USER=store_tests",
                            "-e", "POSTGRES_PASSWORD_FILE=/run/secrets/db_password",
                            "--mount", f"type=bind,source={secret},target=/run/secrets/db_password,readonly",
                            "-p", "127.0.0.1::5432", image], check=True, stdout=subprocess.DEVNULL)
            created = True
            deadline = time.monotonic() + 60
            while subprocess.run(["docker", "exec", name, "pg_isready", "-U", "store_tests", "-d", "store_tests"],
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
                if time.monotonic() >= deadline:
                    raise TimeoutError("The isolated PostgreSQL instance did not become ready")
                time.sleep(1)
            port = subprocess.check_output(["docker", "port", name, "5432"], text=True).strip().rsplit(":", 1)[1]
            environment = {**os.environ, "STORE_TEST_DB_URL": f"jdbc:postgresql://127.0.0.1:{port}/store_tests",
                           "STORE_TEST_DB_USER": "store_tests", "STORE_TEST_DB_PASSWORD": password}
            subprocess.run([str(PROJECT / "gradlew"), "--no-daemon", "--console=plain", "postgresTest"],
                           cwd=PROJECT, env=environment, check=True)
            print("Isolated PostgreSQL: JPA, migrations, rollback and concurrent checkout passed")
        finally:
            if created:
                subprocess.run(["docker", "rm", "-f", name], check=True, stdout=subprocess.DEVNULL)


if __name__ == "__main__":
    main()
