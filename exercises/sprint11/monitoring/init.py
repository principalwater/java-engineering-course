#!/usr/bin/env python3
"""Создаёт пароль только для локального администратора Grafana."""
import os
from pathlib import Path
import secrets

path = Path(__file__).resolve().parent / "target/lab.env"
path.parent.mkdir(exist_ok=True)
if not path.exists():
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as output:
        output.write("GF_SECURITY_ADMIN_PASSWORD=" + secrets.token_hex(32) + "\n")
key, value = path.read_text().strip().split("=", 1)
if key != "GF_SECURITY_ADMIN_PASSWORD" or len(value) != 64 or any(c not in "0123456789abcdef" for c in value):
    raise SystemExit("Invalid Grafana secret file; preserve it and inspect its format")
path.chmod(0o600)
print("Local Grafana password ready; value is not printed")
