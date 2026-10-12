#!/usr/bin/env python3
"""Подготовка локальных secrets без перезаписи существующих паролей."""

import os
from pathlib import Path
import secrets
import sys


SECRET_NAMES = ("db_password", "analytics_db_password", "clickhouse_password")
SECRET_ENTROPY_BYTES = 32
DIRECTORY_MODE = 0o700
FILE_MODE = 0o600
DEFAULT_DIRECTORY = Path.home() / ".config/java-engineering-course/sprint04"


def ensure_secret(path):
    try:
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, FILE_MODE)
    except FileExistsError:
        if path.is_symlink() or not path.is_file():
            raise ValueError(f"Expected a regular secret file: {path}")
        value = path.read_bytes()
        if value.endswith(b"\n"):
            value = value[:-1].removesuffix(b"\r")
        if not value:
            raise ValueError(f"Secret file is empty: {path}")
        path.chmod(FILE_MODE)
    else:
        with os.fdopen(descriptor, "w", encoding="utf-8") as destination:
            destination.write(secrets.token_urlsafe(SECRET_ENTROPY_BYTES) + "\n")


def main():
    directory = Path(os.environ.get("BLOG_SECRETS_DIR") or DEFAULT_DIRECTORY).expanduser()
    script = Path(__file__).resolve()
    repository = next((parent for parent in script.parents if (parent / ".git").exists()), script.parents[3])
    if directory.is_symlink() or directory.resolve().is_relative_to(repository):
        raise ValueError("Secrets directory must be outside the repository and must not be a symlink")
    directory.mkdir(parents=True, mode=DIRECTORY_MODE, exist_ok=True)
    directory.chmod(DIRECTORY_MODE)
    for name in SECRET_NAMES:
        ensure_secret(directory / name)
    print(f"Secret files are ready: {directory}")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError) as exception:
        sys.exit(f"Secret preparation failed: {exception}")
