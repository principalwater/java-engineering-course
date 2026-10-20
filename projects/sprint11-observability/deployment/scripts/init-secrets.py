"""Локальные секреты банка: AES-256 на macOS, private-файлы только для изолированной CI."""

import importlib.util
import os
from pathlib import Path
import re
import secrets
import sys


SECRET_NAMES = (
    "db_admin_password", "db_accounts_password", "db_cash_password", "db_transfer_password",
    "db_notifications_password", "db_analytics_password", "db_keycloak_password", "db_superset_password",
    "authorization_admin_password", "oauth_front_secret", "oauth_insights_secret",
    "oauth_cash_secret", "oauth_transfer_secret", "oauth_analytics_secret", "demo_alice_password", "demo_bob_password",
    "demo_analyst_password", "analytics_admin_password", "analytics_writer_password",
    "analytics_reader_password", "analytics_bi_password", "superset_secret_key", "superset_admin_password",
    "cassandra_admin_password", "cassandra_activity_password",
    "kafka_admin_password", "kafka_analytics_password",
)


def initialize(directory, repository):
    directory = Path(directory).expanduser()
    if directory.is_symlink() or directory.resolve().is_relative_to(repository.resolve()):
        raise ValueError("Secret directory must be a real directory outside the repository")
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    directory.chmod(0o700)
    for name in SECRET_NAMES:
        path = directory / name
        if path.is_symlink():
            raise ValueError("Secret files must not be symlinks")
        try:
            descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        except FileExistsError:
            if not path.is_file() or not re.fullmatch(rb"[a-f0-9]{64}\n?", path.read_bytes()):
                raise ValueError("Secret files must contain a 64-character hexadecimal value")
            path.chmod(0o600)
        else:
            with os.fdopen(descriptor, "w", encoding="utf-8") as output:
                output.write(secrets.token_hex(32) + "\n")
    return directory


def secret_directory(repository):
    directory = os.environ.get("BANK_SECRETS_DIR", Path.home() / ".config/java-engineering-course/sprint10")
    if sys.platform == "darwin":
        specification = importlib.util.spec_from_file_location("secret_disk", Path(__file__).with_name("secret-disk.py"))
        disk = importlib.util.module_from_spec(specification)
        specification.loader.exec_module(disk)
        if "--lock" in sys.argv[1:]:
            disk.lock(directory)
            print("Local bank secret disk is locked")
            sys.exit(0)
        return disk.unlock(directory, repository)
    if "--lock" in sys.argv[1:] or os.environ.get("BANK_ALLOW_PLAINTEXT_TEST_SECRETS") != "true":
        raise RuntimeError("Non-macOS file secrets require BANK_ALLOW_PLAINTEXT_TEST_SECRETS=true in isolated CI; production requires an external secret store")
    return directory


if __name__ == "__main__":
    repository = Path(__file__).resolve().parents[4]
    initialize(secret_directory(repository), repository)
    print("Local bank secrets are ready; existing values were preserved")
