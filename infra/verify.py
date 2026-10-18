#!/usr/bin/env python3
"""Exercise deployed HTTP/database paths, retained data and a clean second plan."""

import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import ssl
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from urllib.parse import urlparse
import uuid

import deploy


def request(url, method="GET", body=None):
    data = None if body is None else json.dumps(body).encode()
    headers = {} if data is None else {"Content-Type": "application/json"}
    context = ssl.create_default_context(cafile=os.environ.get("STORE_TLS_CA")) if url.startswith("https:") else None
    with urlopen(Request(url, data=data, headers=headers, method=method), timeout=15, context=context) as response:
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
    parser.add_argument("--project-dir", type=Path, help="Explicit bank source checkout")
    args = parser.parse_args()
    state = deploy.external_directory(Path(os.environ.get("COURSE_DEPLOY_STATE", Path.home() / ".local/state/java-engineering-course")) / args.stack)
    environment = {**os.environ, "TF_DATA_DIR": str(state / "providers")}
    tool = os.environ.get("TF_CLI", "tofu")
    tofu = [tool, f"-chdir={deploy.INFRA}"]
    deployed = json.loads(subprocess.check_output(tofu + ["output", "-json", "deployment"], env=environment))
    driver = [sys.executable, str(deploy.INFRA / "deploy.py"), args.stack]
    optional = ["--analytics"] if args.analytics else []
    if args.project_dir:
        if args.stack != "sprint09":
            parser.error("--project-dir is supported only for the bank deployment")
        optional += ["--project-dir", str(args.project_dir)]

    def plan(*options):
        with (state / "verification-plan.log").open("w") as log:
            subprocess.run(driver + ["plan"] + optional + list(options), stdout=log, stderr=subprocess.STDOUT, check=True)
        return json.loads(subprocess.check_output(tofu + ["show", "-json", str(state / "reviewed.tfplan")], env=environment))

    if args.stack == "sprint09":
        project = args.project_dir or deploy.REPOSITORY / deploy.STACKS[args.stack]
        secrets_directory = Path(os.environ.get("COURSE_DEPLOY_SECRETS", Path.home() / ".config/java-engineering-course/tofu")) / args.stack
        specification = importlib.util.spec_from_file_location("bank_runtime_verifier", project / "scripts/verify-runtime.py")
        probe = importlib.util.module_from_spec(specification)
        specification.loader.exec_module(probe)
        containers = {name.removeprefix("java-tofu-sprint09-"): name for name in deployed["containers"]}
        for name in containers.values():
            probe.inspect_owned(name)
        users = probe.verify(deployed["frontend_url"], secrets_directory, containers, args.analytics)
        financial_query = """
            SELECT jsonb_build_object(
                'accounts', (SELECT jsonb_agg(jsonb_build_array(id,balance_minor,version) ORDER BY id) FROM accounts.account),
                'operations', (SELECT jsonb_agg(to_jsonb(o) ORDER BY owner_id,type,request_id) FROM accounts.money_operation o),
                'ledger', (SELECT jsonb_agg(to_jsonb(l) ORDER BY sequence) FROM accounts.ledger_entry l)
            )::text
        """
        try:
            expected = probe.pg(containers, financial_query)
            account = users["alice"].account()
            database = containers["database"]
            old_id = subprocess.check_output(["docker", "inspect", "--format", "{{.Id}}", database], text=True).strip()
            address = 'module.sprint09[0].docker_container.platform["database"]'
            replacement = plan("--replace", address)
            changes = [item for item in replacement["resource_changes"] if item["change"]["actions"] != ["no-op"]]
            assert len(changes) == 1 and changes[0]["address"] == address, "Only the native bank database container may be replaced"
            subprocess.run(driver + ["apply"] + optional, check=True)
            new_id = subprocess.check_output(["docker", "inspect", "--format", "{{.Id}}", database], text=True).strip()
            assert old_id != new_id

            def restored():
                try:
                    return probe.pg(containers, financial_query) == expected and users["alice"].account() == account
                except (probe.VerificationError, URLError, TimeoutError, subprocess.SubprocessError):
                    return False

            probe.wait_for("native PostgreSQL financial state and HTTP recovery", restored)

            def authentication_restored():
                try:
                    return probe.User(deployed["frontend_url"], secrets_directory).login("alice")
                except (probe.VerificationError, URLError, TimeoutError):
                    return False

            relogin = probe.wait_for("native OIDC connection recovery after PostgreSQL recreation", authentication_restored)
            try:
                assert relogin.account() == account, "Fresh OIDC login must retain the same account and balance"
            finally:
                relogin.logout()
            print("Native PostgreSQL recreation: exact balances, receipts and ledger retained; HTTP and fresh OIDC login recovered")
            known = deploy.secret_values(secrets_directory)
            deploy.check_secret_values(json.loads((state / "terraform.tfstate").read_text()), known)
            for name in containers.values():
                configuration = subprocess.check_output(["docker", "inspect", "--format", "{{json .Config}}", name], text=True)
                deploy.check_secret_values(json.loads(configuration), known)
            print("Native state and owned container configurations contain no external secret-file values")
        finally:
            for user in users.values():
                if user is not None:
                    user.logout()
    elif args.stack.startswith(deploy.JAR_PREFIXES):
        sprint = args.stack.split("-", 1)[0]
        oauth = "token_url" in deployed
        specification = importlib.util.spec_from_file_location("storefront_smoke", deploy.REPOSITORY / deploy.STACKS[args.stack] / "scripts/smoke.py")
        smoke = importlib.util.module_from_spec(specification)
        specification.loader.exec_module(smoke)
        secrets_directory = Path(os.environ.get("COURSE_DEPLOY_SECRETS", Path.home() / ".config/java-engineering-course/tofu")) / args.stack
        if oauth:
            os.environ["STORE_TLS_CA"] = str(secrets_directory / "tls_ca.pem")
        # OAuth stacks obtain client-credentials tokens from the published Keycloak endpoint; Sprint 7 reads a service token file.
        probe = smoke.MarketProbe(deployed["frontend_url"], payments_url=deployed["payments_url"], secrets_dir=secrets_directory,
                                  **({"token_uri": deployed["token_url"]} if oauth else {}))
        proof = probe.run()
        expected_total = probe.read(proof["order_path"]).order_total

        def payment_survives():
            probe.verify_payment(proof)
            return True

        def payment_balance():
            token = probe.service_token() if oauth else (secrets_directory / "payments_service_token").read_text().strip()
            endpoint = deployed["payments_url"] + "/api/v1/accounts/" + proof["account_id"] + "/balance"
            with urlopen(Request(endpoint, headers={"Authorization": "Bearer " + token}), timeout=10) as response:
                return json.load(response)["balanceMinor"]

        def replace(address, container):
            old_container = subprocess.check_output(["docker", "inspect", "--format", "{{.Id}}", container], text=True).strip()
            replacement = plan("--replace", address)
            changes = [change for change in replacement["resource_changes"] if change["change"]["actions"] != ["no-op"]]
            assert len(changes) == 1 and changes[0]["address"] == address, "Only the selected service may be replaced"
            subprocess.run(driver + ["apply"] + optional, check=True)
            new_container = subprocess.check_output(["docker", "inspect", "--format", "{{.Id}}", container], text=True).strip()
            assert old_container != new_container

        persistent = args.stack.endswith("-postgresql")
        for service in ["storefront", "payments"]:
            key = service + "_database" if persistent else service
            address = (f'module.{sprint}["{args.stack}"].module.postgres["{service}"].docker_container.database' if persistent
                       else f'module.{sprint}["{args.stack}"].docker_container.application["{service}"]')
            replace(address, deployed["containers"][key])
            if persistent:
                await_value(lambda: probe.read(proof["order_path"]).order_total, expected_total)
                await_value(payment_survives, True)
        if persistent:
            print("Independent PostgreSQL recreation: owned order, balance and idempotent ledger receipt survived")
        else:
            if oauth:
                # Restarting H2 removes accounts; a retained Redis session cannot authenticate without its account.
                assert urlparse(probe.read(proof["order_path"]).url).path == "/account/login"
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
            proof = probe.run()
            print("Both H2 services reset their in-memory data and accepted a new purchase")
        if oauth and args.analytics:
            seller = smoke.MarketProbe(deployed["frontend_url"], secrets_dir=secrets_directory)
            seller.login("seller", (secrets_directory / "seller_password").read_text().strip())
            seller.post("/cabinet/store/analytics/sync", seller.read("/cabinet/store/analytics"), days=7)
            await_value(lambda: int(probe.read("/cabinet/analytics").metrics.get("orders", "0")), 1)
            assert int(seller.read("/cabinet/store/analytics").metrics["orders"]) >= 2
            print("ClickHouse: one buyer sees their own order; the seller sees both buyers' paid orders")
        if oauth:
            realm = deployed["token_url"].rsplit("/protocol/", 1)[0]
            assert request(realm + "/.well-known/openid-configuration")["issuer"] == deployed["issuer"]
            balance = deployed["payments_url"] + "/api/v1/accounts/" + proof["account_id"] + "/balance"
            for headers in ({}, {"Authorization": "Bearer invalid-service-token"}):
                try:
                    urlopen(Request(balance, headers=headers), timeout=5).close()
                except HTTPError as error:
                    assert error.code == 401
                else:
                    raise AssertionError("Payments must reject missing or invalid bearer credentials")

            def signing_keys():
                return sorted(key["kid"] for key in request(deployed["jwk_set_url"])["keys"])

            # Unchanged key ids prove the realm came from the retained database, not from a fresh import.
            keys = signing_keys()
            assert keys, "Keycloak must publish signing keys"
            for name, address in [("authorization_database", "module.authorization_postgres[0].docker_container.database"),
                                  ("authorization", "docker_container.authorization[0]")]:
                replace(f'module.{sprint}["{args.stack}"].{address}', deployed["containers"][name])
                await_value(signing_keys, keys)
                await_value(payment_survives, True)
            # Containers and state must hold only paths to secret files, never their contents.
            known = deploy.secret_values(secrets_directory)
            configurations = {"state": (state / "terraform.tfstate").read_text()}
            for name in deployed["containers"].values():
                configurations[name] = subprocess.check_output(["docker", "inspect", "--format", "{{json .Config}}", name], text=True)
            for source, content in configurations.items():
                assert not any(value in content for value in known), f"Secret file value found in {source}"
            print("Keycloak: realm keys, token validation and secret-free containers survived database and server recreation")
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
