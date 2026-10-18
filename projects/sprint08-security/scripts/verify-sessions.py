#!/usr/bin/env python3
"""Общая сессия двух настоящих JVM: смена узла, выход и отказ Redis без локального fallback."""

import copy
import os
import subprocess
from urllib.error import HTTPError
from urllib.parse import urlencode, urlparse
from runtime import PROJECT, applications, initialize, postgres, published_port, redis, run_directory, sessions, wait_for
from smoke import CSRF_FIELD, MarketProbe


# Лимит допускает краткое противодавление, но не позволяет скрыть длительный отказ хранилища.
MAX_PRESSURE_REJECTIONS = 8


def copy_browser(source, target):
    for cookie in source.cookies:
        target.cookies.set_cookie(copy.copy(cookie))


def restart_store(shared, *browsers):
    subprocess.run(["docker", "restart", "--time", "30", shared["container"]], check=True, stdout=subprocess.DEVNULL)
    assert published_port(shared["container"], 6379) == shared["port"], "Redis host port changed on restart"
    # У каждой JVM свой Lettuce-клиент: готовность одного не означает восстановления второго.
    for browser in browsers:
        wait_for(lambda: urlparse(browser.read("/cabinet").url).path == "/cabinet", "Restored shared session")


def memory_pressure(shared, buyer):
    probe_command = 'REDISCLI_AUTH="$(cat /run/secrets/session_redis_password)" redis-cli --user probe --no-auth-warning --raw INFO stats'
    evicted = 0
    rejected = 0
    for index in range(4000):
        # Новый посетитель не возвращает cookie: каждый GET формы создаёт новый CSRF session.
        try:
            MarketProbe(buyer.base, secrets_dir=buyer.secrets_dir).read("/account/login")
        except HTTPError as error:
            if error.code != 503:
                raise
            with error:
                assert error.headers.get("Retry-After") == "1", "Anonymous backpressure must advertise Retry-After: 1"
                assert error.headers.get("Set-Cookie") is None, "A rejected visitor must not receive a session cookie"
                rejected += 1
                assert rejected <= MAX_PRESSURE_REJECTIONS, "Session pressure became a sustained anonymous outage"
        if index % 8 == 0:
            assert urlparse(buyer.read("/cabinet").url).path == "/cabinet", "An active buyer lost access during the bounded flood"
        if index % 128 == 127:
            info = subprocess.check_output(["docker", "exec", shared["container"], "sh", "-c", probe_command], text=True)
            evicted = int(next(line.split(":", 1)[1] for line in info.splitlines() if line.startswith("evicted_keys:")))
            if evicted:
                break
    assert evicted > 0, "The pressure scenario never reached the real Redis memory limit"
    assert urlparse(buyer.read("/cabinet").url).path == "/cabinet", "Memory pressure blocked existing authenticated traffic"
    print("Session memory pressure: bounded anonymous traffic reached eviction; active authenticated traffic passed; "
          + str(rejected) + " explicit anonymous rejections")


def main():
    with run_directory("sessions") as directory:
        secrets_directory = initialize(directory / "secrets")
        with postgres(secrets_directory) as databases, redis(secrets_directory) as cache, \
                sessions(secrets_directory) as shared:
            session_uri = "redis://sessions:" + (secrets_directory / "session_redis_password").read_text().strip() \
                + "@" + shared["host"] + ":" + shared["port"]
            subprocess.run([str(PROJECT / "gradlew"), "--no-daemon", "--console=plain", ":storefront:sessionTest"],
                           cwd=PROJECT, env={**os.environ, "STORE_TEST_SESSIONS_URL": session_uri}, check=True)
            with applications(secrets_directory, cache, databases, shared) as endpoints:
                verify_nodes(endpoints, shared, secrets_directory)


def verify_nodes(endpoints, shared, secrets_directory):
    buyer = MarketProbe(endpoints["storefront"], secrets_dir=secrets_directory)
    buyer.register_account("Общая сессия")
    replica = MarketProbe(endpoints["storefront_replica"], secrets_dir=secrets_directory)
    copy_browser(buyer, replica)
    assert urlparse(replica.read("/cabinet").url).path == "/cabinet", "The second JVM lost authentication"
    restart_store(shared, replica, buyer)
    assert urlparse(buyer.read("/cabinet").url).path == "/cabinet", "The Redis restart lost authentication"
    stolen = MarketProbe(endpoints["storefront"], secrets_dir=secrets_directory)
    copy_browser(buyer, stolen)
    confirmation = replica.read("/account/logout")
    replica.read("/account/logout", urlencode({CSRF_FIELD: confirmation.token}).encode(),
                 "application/x-www-form-urlencoded")
    assert urlparse(buyer.read("/cabinet").url).path == "/account/login", "Logout did not reach the first JVM"
    buyer.register_account("Проверка отказа")
    restart_store(shared, buyer)
    assert urlparse(stolen.read("/cabinet").url).path == "/account/login", "AOF restored a logged-out session"
    memory_pressure(shared, buyer)
    copy_browser(buyer, replica)
    subprocess.run(["docker", "stop", "--time", "30", shared["container"]], check=True, stdout=subprocess.DEVNULL)
    for node in (buyer, replica):
        try:
            node.read("/cabinet")
        except HTTPError as error:
            assert error.code == 503, "Session storage failure must be explicit and fail closed"
            error.read()
        else:
            raise AssertionError("An unavailable session store must not authorize a local fallback")
    print("Shared sessions: two JVMs, AOF restart, cross-node logout and fail-closed Redis outage passed")

if __name__ == "__main__":
    main()
