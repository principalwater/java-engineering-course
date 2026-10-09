"""Проверка реального HTTP-приложения; Python 3.11+, без дополнительных библиотек."""

import argparse
from datetime import datetime
import json
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import urlopen

BASE_URL = "http://127.0.0.1:18082/actuator/"


def get(endpoint: str) -> tuple[int, dict | None]:
    try:
        with urlopen(BASE_URL + endpoint, timeout=5) as response:
            return response.status, json.load(response)
    except HTTPError as error:
        with error:
            return error.code, None


def main() -> None:
    parser = argparse.ArgumentParser(description="Проверка профиля Actuator")
    parser.add_argument("profile", choices=("default", "lab"))
    parser.add_argument(
        "builder", choices=("maven", "gradle"), nargs="?", default="maven"
    )
    args = parser.parse_args()
    profile = args.profile
    status, health = get("health")
    assert status == 200 and health is not None and health["status"] == "UP"
    if profile == "default":
        for endpoint in ("metrics", "env", "info", "beans", "loggers"):
            assert get(endpoint)[0] == 404, endpoint
    else:
        status, metric = get("metrics/system.cpu.count")
        assert status == 200 and metric is not None
        assert metric["name"] == "system.cpu.count" and metric["description"]
        assert metric["measurements"][0]["value"] >= 1 and "value" not in metric
        status, environment = get("env")
        assert status == 200 and environment is not None
        assert isinstance(environment["propertySources"], list)
        for endpoint, field in (("beans", "contexts"), ("loggers", "loggers")):
            status, data = get(endpoint)
            assert status == 200 and data is not None
            assert isinstance(data[field], dict)
        status, info = get("info")
        assert status == 200 and info is not None
        assert info["build"]["artifact"] == "boot-actuator-practice"
        assert info["build"]["version"] == "1.0-SNAPSHOT" and info["build"]["time"]
        report = Path("target/classes" if args.builder == "maven" else "build/resources/main")
        properties = (report / "META-INF/build-info.properties").read_text()
        encoded_time = next(
            line.split("=", 1)[1]
            for line in properties.splitlines()
            if line.startswith("build.time=")
        )
        expected_time = datetime.fromisoformat(encoded_time.replace("\\:", ":"))
        # BuildProperties 3.4 преобразует время к миллисекундам.
        expected_time = expected_time.replace(microsecond=expected_time.microsecond // 1000 * 1000)
        assert datetime.fromisoformat(info["build"]["time"]) == expected_time
    print(f"Actuator: профиль {profile}, все проверки прошли")


if __name__ == "__main__":
    main()
