#!/usr/bin/env python3
"""Exercise deployed HTTP/JDBC paths, retained data and a clean second plan."""

import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from urllib.error import URLError
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

    if args.stack == "mvc-exercise":
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
        try:
            replacement = plan("--replace", address)
            changes = [item for item in replacement["resource_changes"] if item["change"]["actions"] != ["no-op"]]
            assert len(changes) == 1 and changes[0]["address"] == address
            assert "delete" in changes[0]["change"]["actions"] and "create" in changes[0]["change"]["actions"]
            subprocess.run(driver + ["apply"] + optional, check=True)
            new_container = subprocess.check_output(["docker", "inspect", "--format", "{{.Id}}", database], text=True).strip()
            assert old_container != new_container
            await_value(read, marker)
        finally:
            remove()
        print("HTTP/JDBC and retained PostgreSQL data after container recreation: passed")
    repeated = plan()
    assert all(change["change"]["actions"] == ["no-op"] for change in repeated.get("resource_changes", [])), "The repeated deployment plan must be empty"
    print("Repeated OpenTofu plan: no changes")


if __name__ == "__main__":
    main()
