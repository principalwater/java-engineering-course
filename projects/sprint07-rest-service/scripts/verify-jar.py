#!/usr/bin/env python3
"""Проверяет два Executable JAR через настоящий HTTP, H2 и Redis с рабочим ACL."""

import argparse
import subprocess
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import uuid
from runtime import applications, initialize, redis, run_directory
from smoke import MarketProbe


def main():
    parser = argparse.ArgumentParser(description="Verify both executable JARs with native H2 and Redis.")
    parser.parse_args()
    with run_directory("jar") as directory:
        secrets_directory = initialize(directory / "secrets")
        with redis(secrets_directory, administrative=True) as cache, applications(secrets_directory, cache) as endpoints:
            balance = endpoints["payments"] + "/api/v1/accounts/" + str(uuid.uuid4()) + "/balance"
            for headers in ({}, {"Authorization": "Bearer invalid-service-token"}):
                try:
                    urlopen(Request(balance, headers=headers), timeout=5).close()
                except HTTPError as error:
                    assert error.code == 401
                else:
                    raise AssertionError("Payments must reject missing or incorrect service credentials")
            MarketProbe(endpoints["storefront"], endpoints["payments"], secrets_directory).run()
            # Дополнительная fixture-роль наблюдает keyspace; приложение использует неизменённый production ACL.
            scan = ('REDISCLI_AUTH="$(cat /run/secrets/redis_password)" '
                    "redis-cli --user cache_test --no-auth-warning --scan --pattern 'sprint07-jar-test:v1:*'")
            keys = subprocess.check_output(["docker", "exec", cache["container"], "sh", "-c", scan], text=True).splitlines()
            assert any(":product:" in key for key in keys) and any(":page:" in key for key in keys), \
                "The actual storefront ACL must populate both product and page caches"
    print("Both executable JARs: native Netty/H2, authenticated Redis, payments and graceful shutdown passed")


if __name__ == "__main__":
    main()
