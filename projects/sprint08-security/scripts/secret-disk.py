"""Шифрование локальных Compose secrets на macOS; ключ остаётся в Keychain."""

import ctypes
import hashlib
import os
from pathlib import Path
import plistlib
import secrets
import shutil
import subprocess
import uuid


KEYCHAIN_ACCOUNT = b"java-course-local-secrets"
KEYCHAIN_NOT_FOUND = -25300


def disk_path(directory):
    identifier = hashlib.sha256(str(Path(directory).resolve()).encode()).hexdigest()[:24]
    return Path.home() / ".local/share/java-engineering-course/secret-disks" / (identifier + ".sparseimage")


def keychain_password(image):
    # Security.framework получает значение в памяти, а не через argv/env и список процессов.
    security = ctypes.CDLL("/System/Library/Frameworks/Security.framework/Security")
    service = ("java-course-secret-disk:" + image.stem).encode()
    length = ctypes.c_uint32()
    data = ctypes.c_void_p()
    result = security.SecKeychainFindGenericPassword(
        None, len(service), service, len(KEYCHAIN_ACCOUNT), KEYCHAIN_ACCOUNT,
        ctypes.byref(length), ctypes.byref(data), None)
    if result == 0:
        try:
            return ctypes.string_at(data, length.value)
        finally:
            security.SecKeychainItemFreeContent(None, data)
    if result != KEYCHAIN_NOT_FOUND:
        raise RuntimeError("Cannot read the local secret-disk key from Keychain")
    if image.exists():
        raise RuntimeError("Secret-disk key is missing; restore its Keychain entry before continuing")
    password = secrets.token_hex(32).encode()
    result = security.SecKeychainAddGenericPassword(
        None, len(service), service, len(KEYCHAIN_ACCOUNT), KEYCHAIN_ACCOUNT,
        len(password), password, None)
    if result != 0:
        raise RuntimeError("Cannot store the local secret-disk key in Keychain")
    return password


def mounted_image(directory):
    state = plistlib.loads(subprocess.check_output(["hdiutil", "info", "-plist"]))
    for image in state.get("images", []):
        if any(entity.get("mount-point") == str(directory) for entity in image.get("system-entities", [])):
            return Path(image["image-path"]).resolve()
    return None


def require_encryption(image, password):
    metadata = plistlib.loads(subprocess.check_output(
        ["hdiutil", "imageinfo", "-plist", "-stdinpass", str(image)], input=password + b"\0"))
    backing = metadata.get("Backing Store Information", {})
    if not metadata.get("Properties", {}).get("Encrypted") or backing.get("Encryption") != "AES-256":
        raise ValueError("Secret disk must use AES-256 encryption")


def unlock(directory, repository):
    directory = Path(directory).expanduser()
    if directory.is_symlink() or directory.resolve().is_relative_to(repository.resolve()):
        raise ValueError("Secret mount must be a real directory outside the repository")
    directory = directory.resolve()
    image = disk_path(directory)
    mounted = mounted_image(directory)
    if mounted:
        if mounted != image.resolve():
            raise ValueError("Secret mount belongs to another disk image")
        require_encryption(image, keychain_password(image))
        return directory
    directory.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    image.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    image.parent.chmod(0o700)
    if image.is_symlink():
        raise ValueError("Secret disk image must not be a symlink")
    password = keychain_password(image)
    if not image.exists():
        subprocess.run(["hdiutil", "create", "-size", "128m", "-type", "SPARSE", "-fs", "HFS+J",
                        "-volname", "Java course secrets", "-encryption", "AES-256", "-stdinpass", str(image)],
                       input=password + b"\0", check=True, stdout=subprocess.PIPE)
        image.chmod(0o600)
    require_encryption(image, password)
    previous = None
    if directory.exists() and any(directory.iterdir()):
        if any(path.is_symlink() or not path.is_file() for path in directory.iterdir()):
            raise ValueError("Existing secret directory must contain regular files only")
        previous = directory.with_name(directory.name + ".migration-" + uuid.uuid4().hex)
        directory.rename(previous)
    directory.mkdir(exist_ok=True, mode=0o700)
    try:
        subprocess.run(["hdiutil", "attach", str(image), "-stdinpass", "-nobrowse", "-owners", "on",
                        "-mountpoint", str(directory)], input=password + b"\0", check=True, stdout=subprocess.PIPE)
    except BaseException:
        if previous is not None:
            directory.rmdir()
            previous.rename(directory)
        raise
    directory.chmod(0o700)
    if previous is not None:
        # Сначала проверяем каждую копию; ошибка сохраняет исходники для ручного восстановления.
        for source in previous.iterdir():
            destination = directory / source.name
            if destination.exists() and destination.read_bytes() != source.read_bytes():
                raise ValueError("Existing encrypted secret differs from the migration source")
            shutil.copyfile(source, destination)
            destination.chmod(0o600)
            if destination.read_bytes() != source.read_bytes():
                raise RuntimeError("Secret migration verification failed")
        shutil.rmtree(previous)
    return directory


def lock(directory):
    directory = Path(directory).expanduser().resolve()
    if mounted_image(directory) != disk_path(directory).resolve():
        raise ValueError("The expected encrypted secret disk is not mounted")
    containers = subprocess.check_output(["docker", "ps", "-q"], text=True).split()
    if containers:
        import json
        state = json.loads(subprocess.check_output(["docker", "inspect", *containers]))
        if any(Path(mount.get("Source", "/")).is_relative_to(directory)
               for container in state for mount in container.get("Mounts", [])):
            raise ValueError("Stop containers using this secret disk before locking it")
    # Без force: занятый том должен остаться доступным, а не сломать работающий контейнер.
    subprocess.run(["hdiutil", "detach", str(directory)], check=True, stdout=subprocess.PIPE)
