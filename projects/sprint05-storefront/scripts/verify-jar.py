#!/usr/bin/env python3
"""Запускает готовый Executable JAR с новой H2 и проверяет настоящий HTTP-путь."""

import json
from pathlib import Path
import subprocess
import tempfile
import time
from urllib.error import URLError
from urllib.request import urlopen

from smoke import MarketProbe

PROJECT = Path(__file__).resolve().parents[1]
TEST_HTTP_PORT = 18095


def main():
    jar = PROJECT / "build/libs/storefront.jar"
    if not jar.is_file():
        raise FileNotFoundError("Package storefront.jar with bootJar before verification")
    base = f"http://127.0.0.1:{TEST_HTTP_PORT}"
    with tempfile.TemporaryDirectory(prefix="storefront-jar-") as directory:
        with (Path(directory) / "application.log").open("wb") as log:
            process = subprocess.Popen(["java", "-jar", str(jar), "--spring.profiles.active=h2",
                                        "--store.analytics.enabled=false", "--server.address=127.0.0.1",
                                        f"--server.port={TEST_HTTP_PORT}"], cwd=directory,
                                       stdout=log, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 60
                while True:
                    if process.poll() is not None:
                        raise RuntimeError("The executable JAR exited before readiness")
                    try:
                        with urlopen(base + "/actuator/health/readiness", timeout=3) as response:
                            if json.load(response)["status"] == "UP":
                                break
                    except (URLError, TimeoutError):
                        pass
                    if time.monotonic() >= deadline:
                        raise TimeoutError("The executable JAR did not become ready")
                    time.sleep(1)
                with urlopen(base + "/actuator/info", timeout=3) as response:
                    assert json.load(response)["build"]["name"] == "storefront"
                MarketProbe(base).run()
                print("Executable JAR: embedded Tomcat, native H2, readiness and real HTTP passed")
            finally:
                process.terminate()
                try:
                    process.wait(timeout=25)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
                    raise RuntimeError("The executable JAR exceeded its shutdown budget")
                assert process.returncode in (0, 143, -15), "Unexpected executable JAR termination"


if __name__ == "__main__":
    main()
