#!/usr/bin/env python3
"""Проверяет настоящий Redis: TTL, инвалидацию, границы данных и восстановление соединения."""

import os
import subprocess

from runtime import PROJECT, initialize, redis, run_directory


def main():
    with run_directory("cache") as directory:
        secrets_directory = initialize(directory / "secrets")
        with redis(secrets_directory, administrative=True) as cache:
            password = (secrets_directory / "redis_password").read_text().strip()
            environment = {**os.environ,
                           "STORE_TEST_REDIS_URL": f"redis://cache_test:{password}@{cache['host']}:{cache['port']}"}
            subprocess.run([str(PROJECT / "gradlew"), "--no-daemon", "--console=plain", "redisTest"],
                           cwd=PROJECT, env=environment, check=True)
    print("Isolated Redis: public product cache, private quantities, TTL, invalidation and recovery passed")


if __name__ == "__main__":
    main()
