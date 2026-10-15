#!/usr/bin/env python3
"""Exercise deployed HTTP/database paths, retained data and a clean second plan."""

import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
import uuid

import deploy


def request(url, method="GET", body=None):
    data = None if body is None else json.dumps(body).encode()
    headers = {} if data is None else {"Content-Type": "application/json"}
    with urlopen(Request(url, data=data, headers=headers, method=method), timeout=15) as response:
        raw = response.read()
        return json.loads(raw) if response.headers.get_content_type() == "application/json" else raw


def await_value(read, expected):
    deadline = time.monotonic() + 60
    while True:
        try:
            value = read()
            if value == expected:
                return
        except (URLError, TimeoutError):
            pass
        if time.monotonic() >= deadline:
            raise AssertionError("The persisted value did not recover after database recreation")
        time.sleep(1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stack", choices=deploy.STACKS)
    parser.add_argument("--analytics", action="store_true")
    args = parser.parse_args()
    state = deploy.external_directory(Path(os.environ.get("COURSE_DEPLOY_STATE", Path.home() / ".local/state/java-engineering-course")) / args.stack)
    environment = {**os.environ, "TF_DATA_DIR": str(state / "providers")}
    tool = os.environ.get("TF_CLI", "tofu")
    tofu = [tool, f"-chdir={deploy.INFRA}"]
    deployed = json.loads(subprocess.check_output(tofu + ["output", "-json", "deployment"], env=environment))
    driver = [sys.executable, str(deploy.INFRA / "deploy.py"), args.stack]
    optional = ["--analytics"] if args.analytics else []

    def plan(*options):
        with (state / "verification-plan.log").open("w") as log:
            subprocess.run(driver + ["plan"] + optional + list(options), stdout=log, stderr=subprocess.STDOUT, check=True)
        return json.loads(subprocess.check_output(tofu + ["show", "-json", str(state / "reviewed.tfplan")], env=environment))

    if args.stack.startswith("sprint07-"):
        specification = importlib.util.spec_from_file_location("storefront_smoke", deploy.REPOSITORY / deploy.STACKS[args.stack] / "scripts/smoke.py")
        smoke = importlib.util.module_from_spec(specification)
        specification.loader.exec_module(smoke)
        secrets_directory = Path(os.environ.get("COURSE_DEPLOY_SECRETS", Path.home() / ".config/java-engineering-course/tofu")) / args.stack
        probe = smoke.MarketProbe(deployed["frontend_url"], payments_url=deployed["payments_url"],
                                  secrets_dir=secrets_directory)
        proof = probe.run()
        expected_total = probe.read(proof["order_path"]).order_total

        def payment_survives():
            probe.verify_payment(proof)
            return True

        def payment_balance():
            token = (secrets_directory / "payments_service_token").read_text().strip()
            endpoint = deployed["payments_url"] + "/api/v1/accounts/" + proof["account_id"] + "/balance"
            with urlopen(Request(endpoint, headers={"Authorization": "Bearer " + token}), timeout=10) as response:
                return json.load(response)["balanceMinor"]

        persistent = args.stack.endswith("-postgresql")
        for service in ["storefront", "payments"]:
            key = service + "_database" if persistent else service
            container = deployed["containers"][key]
            old_container = subprocess.check_output(["docker", "inspect", "--format", "{{.Id}}", container], text=True).strip()
            address = (f'module.sprint07["{args.stack}"].module.postgres["{service}"].docker_container.database' if persistent
                       else f'module.sprint07["{args.stack}"].docker_container.application["{service}"]')
            replacement = plan("--replace", address)
            changes = [change for change in replacement["resource_changes"] if change["change"]["actions"] != ["no-op"]]
            assert len(changes) == 1 and changes[0]["address"] == address, "Only the selected service may be replaced"
            subprocess.run(driver + ["apply"] + optional, check=True)
            new_container = subprocess.check_output(["docker", "inspect", "--format", "{{.Id}}", container], text=True).strip()
            assert old_container != new_container
            if persistent:
                await_value(lambda: probe.read(proof["order_path"]).order_total, expected_total)
                await_value(payment_survives, True)
        if persistent:
            print("Independent PostgreSQL recreation: owned order, balance and idempotent ledger receipt survived")
        else:
            try:
                probe.read(proof["order_path"])
            except HTTPError as error:
                assert error.code == 404
            else:
                raise AssertionError("A new H2 storefront must not retain the previous order")
            assert not probe.read("/items").products
            await_value(payment_balance, proof["balance_minor"] + proof["payment_amount_minor"])
            # A fresh account/purchase proves both new JVMs can cooperate after their memory reset.
            probe.run()
            print("Both H2 services reset their in-memory data and accepted a new purchase")
    elif args.stack.startswith(("sprint05", "sprint06")):
        specification = importlib.util.spec_from_file_location("storefront_smoke", deploy.REPOSITORY / deploy.STACKS[args.stack] / "scripts/smoke.py")
        smoke = importlib.util.module_from_spec(specification)
        specification.loader.exec_module(smoke)
        probe = smoke.MarketProbe(deployed["frontend_url"])
        proof = probe.run()
        if args.analytics:
            dashboard = probe.read("/analytics")
            probe.post("/analytics/sync", dashboard, days=7)
            # A concurrent delivery may return before its owner has published the dashboard facts.
            await_value(lambda: int(probe.read("/analytics").metrics.get("orders", "0")) >= 1, True)
        persistent = args.stack.endswith("-postgresql")
        sprint = args.stack.split("-", 1)[0]
        service = "database" if persistent else "backend"
        container = deployed["containers"][service]
        old_container = subprocess.check_output(["docker", "inspect", "--format", "{{.Id}}", container], text=True).strip()
        address = (f'module.{sprint}["{args.stack}"].module.postgres[0].docker_container.database' if persistent
                   else f'module.{sprint}["{args.stack}"].docker_container.application')
        replacement = plan("--replace", address)
        changes = [change for change in replacement["resource_changes"] if change["change"]["actions"] != ["no-op"]]
        assert len(changes) == 1 and changes[0]["address"] == address
        subprocess.run(driver + ["apply"] + optional, check=True)
        new_container = subprocess.check_output(["docker", "inspect", "--format", "{{.Id}}", container], text=True).strip()
        assert old_container != new_container
        if persistent:
            await_value(lambda: probe.read(proof["order_path"]).order_total, 400)
            print("Storefront PostgreSQL: order and cookie ownership survived database recreation")
        else:
            try:
                probe.read(proof["order_path"])
            except HTTPError as error:
                assert error.code == 404
            else:
                raise AssertionError("The recreated in-memory H2 must not retain previous orders")
            assert not probe.read("/items").products
            if args.analytics:
                assert int(probe.read("/analytics").metrics["orders"]) == 0
                print("Storefront H2: the new dataset excludes retained facts from the previous JVM")
            print("Storefront H2: a new JVM received an empty catalog and order history")
    elif args.stack == "mvc-exercise":
        assert b"Hello, world!" in request(deployed["frontend_url"] + "/home")
        assert any(user["firstName"] == "Анна" for user in request(deployed["frontend_url"] + "/users"))
    else:
        database = deployed["containers"]["database"]
        old_container = subprocess.check_output(["docker", "inspect", "--format", "{{.Id}}", database], text=True).strip()
        if args.stack == "docker-exercise":
            base = deployed["frontend_url"]
            assert request(base + "/hello").decode() == "Добро пожаловать на борт!"
            assert any(movie["id"] == 1 for movie in request(base + "/movies"))
            marker = "Persistence " + uuid.uuid4().hex
            identifier = 1_000_000 + uuid.uuid4().int % 1_000_000_000
            subprocess.run(["docker", "exec", database, "psql", "-U", "study", "-d", "study", "-v", "ON_ERROR_STOP=1", "-c",
                            f"INSERT INTO movies VALUES ({identifier}, '{marker}', 'Fixture', 2026)"], check=True, stdout=subprocess.DEVNULL)
            read = lambda: next((item["name"] for item in request(base + "/movies") if item["id"] == identifier), None)
            remove = lambda: subprocess.run(["docker", "exec", database, "psql", "-U", "study", "-d", "study", "-c",
                                             f"DELETE FROM movies WHERE id={identifier}"], check=True, stdout=subprocess.DEVNULL)
            address = "module.docker_exercise[0].module.postgres.docker_container.database"
        else:
            base = deployed["backend_url"]
            if args.stack == "sprint04":
                specification = importlib.util.spec_from_file_location("blog_smoke", deploy.REPOSITORY / deploy.STACKS[args.stack] / "scripts/smoke.py")
                smoke = importlib.util.module_from_spec(specification)
                specification.loader.exec_module(smoke)
                smoke.main(base, deployed["frontend_url"])
            marker = "Persistence " + uuid.uuid4().hex
            post = request(base + "/api/posts", "POST", {"title": marker, "text": "UTF-8 запись", "tags": ["tofu"]})
            path = base + "/api/posts/" + str(post["id"])
            read = lambda: request(path)["title"]
            remove = lambda: request(path, "DELETE")
            address = f"module.{args.stack}[0].module.postgres.docker_container.database"
            if args.analytics:
                secrets_directory = Path(os.environ.get("COURSE_DEPLOY_SECRETS", Path.home() / ".config/java-engineering-course/tofu")) / args.stack
                password = (secrets_directory / "clickhouse_password").read_text().rstrip("\r\n")

                def analytics_count():
                    query = f"SELECT count() FROM postgresql(blog_pg, table='posts') WHERE title='{marker}'"
                    with urlopen(Request(deployed["analytics_url"], data=query.encode(),
                                         headers={"X-ClickHouse-User": "analyst", "X-ClickHouse-Key": password}), timeout=15) as response:
                        return int(response.read())

                assert analytics_count() == 1
        try:
            replacement = plan("--replace", address)
            changes = [item for item in replacement["resource_changes"] if item["change"]["actions"] != ["no-op"]]
            assert len(changes) == 1 and changes[0]["address"] == address
            assert "delete" in changes[0]["change"]["actions"] and "create" in changes[0]["change"]["actions"]
            subprocess.run(driver + ["apply"] + optional, check=True)
            new_container = subprocess.check_output(["docker", "inspect", "--format", "{{.Id}}", database], text=True).strip()
            assert old_container != new_container
            await_value(read, marker)
            if args.analytics:
                await_value(analytics_count, 1)
                print("Read-only ClickHouse queries reflect real PostgreSQL writes: passed")
        finally:
            remove()
        print("HTTP/JDBC and retained PostgreSQL data after container recreation: passed")
    repeated = plan()
    assert all(change["change"]["actions"] == ["no-op"] for change in repeated.get("resource_changes", [])), "The repeated deployment plan must be empty"
    print("Repeated OpenTofu plan: no changes")


if __name__ == "__main__":
    main()
