#!/usr/bin/env python3
"""Сравнивает реальные runtime-зависимости и OpenAPI Java двух независимых сборщиков."""

import hashlib
from pathlib import Path
import zipfile

from runtime import PROJECT, SERVICES


def libraries(path):
    with zipfile.ZipFile(path) as archive:
        return {Path(name).name: hashlib.sha256(archive.read(name)).hexdigest()
                for name in archive.namelist() if name.startswith("BOOT-INF/lib/") and name.endswith(".jar")}


def generated(directory):
    files = {str(path.relative_to(directory)): hashlib.sha256(path.read_bytes()).hexdigest()
             for path in directory.rglob("*.java")}
    if not files:
        raise ValueError("Generate OpenAPI Java before comparing builds: " + str(directory))
    return files


def main():
    for service in SERVICES:
        directory = PROJECT / service
        gradle = libraries(directory / "build/libs" / (service + ".jar"))
        maven = libraries(directory / "target" / (service + ".jar"))
        if gradle != maven:
            differences = sorted(name for name in gradle.keys() | maven.keys() if gradle.get(name) != maven.get(name))
            raise AssertionError(service + " runtime dependencies differ: " + ", ".join(differences))
        gradle_api = generated(directory / "build/generated/openapi/src/main/java")
        maven_api = generated(directory / "target/generated-sources/openapi/src/main/java")
        if gradle_api != maven_api:
            differences = sorted(name for name in gradle_api.keys() | maven_api.keys()
                                 if gradle_api.get(name) != maven_api.get(name))
            raise AssertionError(service + " generated API differs: " + ", ".join(differences))
    print("Gradle and Maven: identical runtime dependency bytes and generated client/server Java")


if __name__ == "__main__":
    main()
