"""Создаёт локальный пароль один раз; не перезаписывает существующий файл."""
from pathlib import Path
import os
import secrets

path = Path(__file__).parent / "target/lab.env"
path.parent.mkdir(exist_ok=True)
try:
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
except FileExistsError:
    print("Local configuration already exists")
else:
    with os.fdopen(fd, "w") as stream:
        stream.write("POSTGRES_USER=study\nPOSTGRES_DB=study\nPOSTGRES_PASSWORD=" + secrets.token_hex(24) + "\n")
    print("Local configuration created")
