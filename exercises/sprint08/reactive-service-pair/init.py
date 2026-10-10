#!/usr/bin/env python3
"""Создаёт только локальные секреты учебного стенда; повторный запуск сохраняет их."""
import os
from pathlib import Path
import secrets

path = Path(__file__).resolve().parent / "target/lab.env"
path.parent.mkdir(exist_ok=True)
keys = ("COURSE_CLIENT_SECRET", "COURSE_USER_PASSWORD", "COURSE_VIEWER_SECRET",
        "COURSE_WRONG_AUDIENCE_SECRET", "KC_BOOTSTRAP_ADMIN_PASSWORD")
if not path.exists():
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as output:
        output.write("".join(f"{key}={secrets.token_hex(32)}\n" for key in keys))
values = dict(line.split("=", 1) for line in path.read_text().splitlines())
if set(values) != set(keys) or any(len(value) != 64 or any(c not in "0123456789abcdef" for c in value) for value in values.values()):
    raise SystemExit("Invalid local secret file; preserve it and inspect its format")
path.chmod(0o600)
print("Local service secrets ready; values are not printed")
