#!/usr/bin/env python3
"""Gradle собирает и проверяет два Executable JAR для Docker и OpenTofu."""

import argparse
from pathlib import Path
import subprocess
import zipfile

PROJECT = Path(__file__).resolve().parents[1]
SERVICES = ("storefront", "payments")


def main():
    parser = argparse.ArgumentParser(description="Package and verify both executable Gradle JARs.")
    parser.parse_args()
    subprocess.run([str(PROJECT / "gradlew"), "--no-daemon", "--console=plain", "check", "packageDeployment"],
                   cwd=PROJECT, check=True)
    for service in SERVICES:
        source = PROJECT / "build/deployment" / (service + ".jar")
        with zipfile.ZipFile(source) as archive:
            manifest = archive.read("META-INF/MANIFEST.MF").decode()
            if "Main-Class: org.springframework.boot.loader.launch.JarLauncher" not in manifest:
                raise ValueError("Expected a Spring Boot executable JAR: " + service)
            libraries = [name for name in archive.namelist() if name.startswith("BOOT-INF/lib/")]
            if not any("reactor-netty-http-" in name for name in libraries) or any("/tomcat-embed-core-" in name or "/jetty-server-" in name for name in libraries):
                raise ValueError("Expected embedded Reactor Netty without a servlet container: " + service)
    print("Both executable JARs are ready in build/deployment")


if __name__ == "__main__":
    main()
