#!/usr/bin/env python3
"""Проверяет обе изолированные PostgreSQL и настоящий HTTP-путь двух JAR."""

import os
import subprocess

from runtime import PROJECT, applications, initialize, postgres, redis, run_directory
from smoke import MarketProbe


def main():
    with run_directory("postgresql") as directory:
        secrets_directory = initialize(directory / "secrets")
        with postgres(secrets_directory) as databases, redis(secrets_directory) as cache:
            environment = dict(os.environ)
            for service, prefix in [("storefront", "STORE_TEST_DB"), ("payments", "PAYMENTS_TEST_DB")]:
                database = databases[service]
                environment.update({prefix + "_URL": database["url"], prefix + "_USER": database["user"],
                                    prefix + "_PASSWORD": database["password_file"].read_text().strip(),
                                    prefix + "_PLATFORM": "postgresql"})
            subprocess.run([str(PROJECT / "gradlew"), "--no-daemon", "--console=plain", "postgresTest"],
                           cwd=PROJECT, env=environment, check=True)
            with applications(secrets_directory, cache, databases=databases) as endpoints:
                MarketProbe(endpoints["storefront"], endpoints["payments"], secrets_directory).run()
    print("Independent PostgreSQL: R2DBC, rollback, concurrent checkout and real payment HTTP passed")


if __name__ == "__main__":
    main()
