"""Локальные file secrets вне Git: существующие значения не меняются."""

import hashlib
import os
from pathlib import Path
import secrets
import stat
import tempfile


SECRET_NAMES = ("db_password", "payments_db_password", "payments_runtime_password", "oauth_client_secret", "authorization_db_password", "authorization_admin_password", "redis_password", "seller_password",
                "analytics_admin_password", "analytics_writer_password", "analytics_reader_password", "analytics_cluster_secret")


def initialize(directory, repository):
    directory = Path(directory).expanduser()
    if directory.is_symlink():
        raise ValueError("Secret directory must not be a symlink")
    directory = directory.resolve()
    if directory.is_relative_to(repository.resolve()):
        raise ValueError("Secret directory must be outside the repository")
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    directory.chmod(0o700)
    for name in SECRET_NAMES:
        path = directory / name
        if path.is_symlink():
            raise ValueError("Secret files must not be symlinks")
        try:
            descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        except FileExistsError:
            if not path.is_file() or not path.read_bytes().strip():
                raise ValueError("Secret files must contain a non-empty value")
            path.chmod(0o600)
        else:
            with os.fdopen(descriptor, "w", encoding="utf-8") as output:
                output.write(secrets.token_hex(32) + "\n")
        if stat.S_IMODE(path.stat().st_mode) != 0o600:
            raise ValueError("Secret file permissions must be 0600")
    acl = directory / "redis_users.acl"
    if acl.is_symlink():
        raise ValueError("Secret files must not be symlinks")
    password_hash = hashlib.sha256((directory / "redis_password").read_bytes().strip()).hexdigest()
    # ACL разрешает только публичные записи каталога; баланс, сессии и корзины сюда не попадают.
    content = ("user default off\nuser storefront on #" + password_hash +
               " ~*:v1:*:product:* ~*:v1:*:page:* -@all +get +mget +set +del +unlink +pttl +ping +hello"
               " +client|setname +client|setinfo\n")
    descriptor, temporary = tempfile.mkstemp(prefix=".redis-acl-", dir=directory)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            output.write(content)
        os.replace(temporary, acl)
    finally:
        Path(temporary).unlink(missing_ok=True)
    return directory


if __name__ == "__main__":
    repository = Path(__file__).resolve().parents[3]
    directory = os.environ.get("STORE_SECRETS_DIR", Path.home() / ".config/java-engineering-course/sprint08")
    initialize(directory, repository)
    print("Local secrets are ready; existing values were preserved")
