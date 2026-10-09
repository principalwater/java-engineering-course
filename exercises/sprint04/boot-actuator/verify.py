"""Проверка реального HTTP-приложения; Python 3.11+, без дополнительных библиотек."""

import argparse
import json
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
    profile = parser.parse_args().profile
    status, health = get("health")
    assert status == 200 and health is not None and health["status"] == "UP"
    if profile == "default":
        for endpoint in ("metrics", "env", "info"):
            assert get(endpoint)[0] == 404, endpoint
    else:
        status, metric = get("metrics/system.cpu.count")
        assert status == 200 and metric is not None
        assert metric["name"] == "system.cpu.count" and metric["description"]
        assert metric["measurements"][0]["value"] >= 1 and "value" not in metric
        status, environment = get("env")
        assert status == 200 and environment is not None
        assert isinstance(environment["propertySources"], list)
        status, info = get("info")
        assert status == 200 and info is not None
        assert info["build"]["artifact"] == "boot-actuator-practice"
        assert info["build"]["version"] == "1.0-SNAPSHOT" and info["build"]["time"]
    print(f"Actuator: профиль {profile}, все проверки прошли")


if __name__ == "__main__":
    main()
