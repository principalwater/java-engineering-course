#!/usr/bin/env python3
"""Создаёт локальные секреты; существующие значения сохраняются."""
import json
import os
from pathlib import Path
import secrets

ROOT = Path(__file__).resolve().parent
TARGET = ROOT / "target"


def initialize():
    TARGET.mkdir(exist_ok=True)
    secret_file = TARGET / "credentials.json"
    if not secret_file.exists():
        credentials = {name: secrets.token_urlsafe(32)
                       for name in ("elastic", "kibana_system", "coffee_writer", "study")}
        credentials["encryption_key"] = secrets.token_hex(32)
        descriptor = os.open(secret_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w") as output:
            json.dump(credentials, output)
    else:
        if secret_file.stat().st_mode & 0o077:
            raise SystemExit("Credentials must have mode 0600")
        credentials = json.loads(secret_file.read_text())
    values = {
        "elasticsearch.env": {"ELASTIC_PASSWORD": credentials["elastic"]},
        "logstash.env": {"COFFEE_WRITER_PASSWORD": credentials["coffee_writer"]},
        "kibana.env": {
            "ELASTICSEARCH_PASSWORD": credentials["kibana_system"],
            "XPACK_SECURITY_ENCRYPTIONKEY": credentials["encryption_key"],
            "XPACK_ENCRYPTEDSAVEDOBJECTS_ENCRYPTIONKEY": credentials["encryption_key"],
            "XPACK_REPORTING_ENCRYPTIONKEY": credentials["encryption_key"],
        },
    }
    for name, data in values.items():
        path = TARGET / name
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, "w") as output:
            output.writelines(f"{key}={value}\n" for key, value in data.items())
    for name in ("logs", "output"):
        (TARGET / name).mkdir(exist_ok=True)
    # UID Logstash отличается от UID хоста Linux; здесь только обезличенные результаты, не секреты.
    (TARGET / "output").chmod(0o1777)
    (TARGET / "logs/coffee.log").touch(exist_ok=True)
    print("Local credentials and directories ready")


if __name__ == "__main__":
    initialize()
