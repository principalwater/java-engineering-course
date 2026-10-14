#!/usr/bin/env python3
"""Оба сборщика готовят одинаковые пути Executable JAR для Docker и OpenTofu."""

import argparse
from pathlib import Path
import shutil
import subprocess
import zipfile

PROJECT = Path(__file__).resolve().parents[1]
SERVICES = ("storefront", "payments")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-tool", choices=["gradle", "maven"], default="gradle")
    args = parser.parse_args()
    if args.build_tool == "gradle":
        command = [str(PROJECT / "gradlew"), "--no-daemon", "--console=plain", "check", "packageDeployment"]
    else:
        command = [str(PROJECT / "mvnw"), "-B", "--no-transfer-progress", "verify"]
    subprocess.run(command, cwd=PROJECT, check=True)
    destination = PROJECT / "build/deployment"
    destination.mkdir(parents=True, exist_ok=True)
    for service in SERVICES:
        source = PROJECT / service / ("build/libs" if args.build_tool == "gradle" else "target") / (service + ".jar")
        with zipfile.ZipFile(source) as archive:
            manifest = archive.read("META-INF/MANIFEST.MF").decode()
            if "Main-Class: org.springframework.boot.loader.launch.JarLauncher" not in manifest:
                raise ValueError("Expected a Spring Boot executable JAR: " + service)
            libraries = [name for name in archive.namelist() if name.startswith("BOOT-INF/lib/")]
            if not any("reactor-netty-http-" in name for name in libraries) or any("/tomcat-embed-core-" in name or "/jetty-server-" in name for name in libraries):
                raise ValueError("Expected embedded Reactor Netty without a servlet container: " + service)
        temporary = destination / (service + ".jar.tmp")
        shutil.copyfile(source, temporary)
        temporary.replace(destination / (service + ".jar"))
    print("Both executable JARs are ready in build/deployment")


if __name__ == "__main__":
    main()
