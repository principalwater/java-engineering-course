"""Общий жизненный цикл изолированных проверок: файлы секретов, реальные сервисы и graceful shutdown."""

from contextlib import contextmanager, ExitStack
import hashlib
import importlib.util
import ipaddress
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
from urllib.error import URLError
from urllib.request import urlopen
import uuid
import zipfile

PROJECT = Path(__file__).resolve().parents[1]
SERVICES = ("storefront", "payments")
JVM_OPTIONS = ["-Xmx192m", "-XX:MaxDirectMemorySize=48m", "-XX:ActiveProcessorCount=1", "-XX:+ExitOnOutOfMemoryError"]
REDIS_COMMAND = ["--bind", "0.0.0.0", "--protected-mode", "yes", "--aclfile", "/run/secrets/redis_users.acl",
                 "--maxmemory", "32mb", "--maxmemory-policy", "allkeys-lfu", "--save", "", "--appendonly", "no", "--dir", "/tmp"]


@contextmanager
def run_directory(purpose):
    root = Path.home() / ".local/state/java-engineering-course/sprint08-tests"
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    root.chmod(0o700)
    directory = root / (purpose + "-" + uuid.uuid4().hex)
    directory.mkdir(mode=0o700)
    try:
        yield directory
    finally:
        print("Retained verification artifacts: " + str(directory))


def stop_containers(names):
    failures = []
    for name in names:
        try:
            owner = subprocess.check_output(["docker", "inspect", "--format",
                                             '{{index .Config.Labels "dev.principalwater.deployment"}}', name], text=True).strip()
            if owner != name:
                raise ValueError("Refusing to stop a container without the expected verification owner: " + name)
            subprocess.run(["docker", "stop", "--time", "30", name], check=True, stdout=subprocess.DEVNULL)
            running = subprocess.check_output(["docker", "inspect", "--format", "{{.State.Running}}", name], text=True).strip()
            if running != "false":
                raise RuntimeError("Verification container is still running: " + name)
        except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError) as error:
            failures.append(str(error))
    if failures:
        raise RuntimeError("Verification cleanup failed: " + "; ".join(failures))


def initialize(directory):
    specification = importlib.util.spec_from_file_location("store_secret_initializer", PROJECT / "scripts/init-secrets.py")
    initializer = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(initializer)
    return initializer.initialize(directory, PROJECT.parents[1])


def configuration(*files):
    command = ["docker", "compose"]
    for name in files or ("compose.yaml",):
        command += ["-f", name]
    return json.loads(subprocess.check_output(command + ["config", "--format", "json"], cwd=PROJECT))


def published_port(container, internal):
    return subprocess.check_output(["docker", "port", container, str(internal)], text=True).strip().rsplit(":", 1)[1]


def wait_for(read, description, timeout=60):
    deadline = time.monotonic() + timeout
    while True:
        try:
            if read():
                return
        except (URLError, TimeoutError, ConnectionResetError):
            pass
        if time.monotonic() >= deadline:
            raise TimeoutError(description + " did not become ready")
        time.sleep(0.25)


@contextmanager
def redis(secrets_directory, administrative=False):
    image = configuration()["services"]["redis"]["image"]
    name = "sprint08-redis-test-" + uuid.uuid4().hex
    if administrative:
        password = (secrets_directory / "redis_password").read_bytes().strip()
        digest = hashlib.sha256(password).hexdigest()
        with (secrets_directory / "redis_users.acl").open("a") as acl:
            acl.write("user cache_test on #" + digest + " ~sprint08-cache-test:* -@all"
                      " +get +mget +set +del +unlink +exists +pttl +ping +hello +scan +pexpire +expire"
                      " +client|setname +client|setinfo\n")
    command = ["docker", "run", "-d", "--name", name, "--user", f"{os.getuid()}:{os.getgid()}",
               "--memory", "128m", "--memory-swap", "128m", "--cpus", "1", "--read-only",
               "--tmpfs", "/tmp:rw,noexec,nosuid,size=1m,mode=1777", "--tmpfs", "/data:rw,noexec,nosuid,size=1m",
               "--label", "dev.principalwater.deployment=" + name, "-p", "127.0.0.1::6379",
               "--entrypoint", "redis-server"]
    for filename in ("redis_users.acl", "redis_password"):
        command += ["--mount", f"type=bind,source={secrets_directory / filename},target=/run/secrets/{filename},readonly"]
    created = False
    try:
        subprocess.run(command + [image] + REDIS_COMMAND, check=True, stdout=subprocess.DEVNULL)
        created = True
        ping = 'REDISCLI_AUTH="$(cat /run/secrets/redis_password)" redis-cli --user storefront --no-auth-warning ping'
        wait_for(lambda: subprocess.run(["docker", "exec", name, "sh", "-c", ping],
                                       capture_output=True).stdout.strip() == b"PONG", "Isolated Redis")
        forbidden = ('REDISCLI_AUTH="$(cat /run/secrets/redis_password)" '
                     'redis-cli --user storefront --no-auth-warning SET financial:balance 1')
        denial = subprocess.check_output(["docker", "exec", name, "sh", "-c", forbidden], text=True)
        assert "NOPERM" in denial, "The catalog cache user must not write financial keys"
        yield {"container": name, "host": "127.0.0.1", "port": published_port(name, 6379)}
    finally:
        if created:
            stop_containers([name])


@contextmanager
def postgres(secrets_directory):
    image = configuration()["services"]["store-db"]["image"]
    names = []
    databases = {}
    try:
        for service in SERVICES:
            name = "sprint08-pg-test-" + service + "-" + uuid.uuid4().hex
            database = "store_tests" if service == "storefront" else "payments_tests"
            filename = "db_password" if service == "storefront" else "payments_db_password"
            subprocess.run(["docker", "run", "-d", "--name", name, "--memory", "256m", "--memory-swap", "256m",
                            "--cpus", "1", "--mount", f"type=volume,source={name}-data,target=/var/lib/postgresql/data",
                            "--label", "dev.principalwater.deployment=" + name,
                            "-e", "POSTGRES_DB=" + database, "-e", "POSTGRES_USER=" + database,
                            "-e", "POSTGRES_PASSWORD_FILE=/run/secrets/db_password",
                            "--mount", f"type=bind,source={secrets_directory / filename},target=/run/secrets/db_password,readonly",
                            "-p", "127.0.0.1::5432", image], check=True, stdout=subprocess.DEVNULL)
            names.append(name)
            wait_for(lambda: subprocess.run(["docker", "exec", name, "pg_isready", "-h", "127.0.0.1", "-U", database, "-d", database],
                                           capture_output=True).returncode == 0, "Isolated PostgreSQL")
            port = published_port(name, 5432)
            databases[service] = {"url": f"r2dbc:postgresql://127.0.0.1:{port}/{database}", "user": database,
                                  "password_file": secrets_directory / filename, "container": name}
        payments = databases["payments"]
        schema = (PROJECT / "payments/src/main/resources/db/schema.sql").read_text()
        grants = (PROJECT / "payments/src/main/resources/db/postgresql-runtime-grants.sql").read_text()
        password = (secrets_directory / "payments_runtime_password").read_text().strip()
        subprocess.run(["docker", "exec", "-i", payments["container"], "psql", "-U", payments["user"],
                        "-d", payments["user"], "-v", "ON_ERROR_STOP=1"],
                       input=schema + "\nCREATE ROLE payments_runtime LOGIN PASSWORD '" + password + "';\n" + grants,
                       text=True, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        payments["runtime_user"] = "payments_runtime"
        payments["runtime_password_file"] = secrets_directory / "payments_runtime_password"
        yield databases
    finally:
        stop_containers(reversed(names))


def configtree(service, secrets_directory, destination, database):
    destination.mkdir(mode=0o700)
    mapping = ({} if service == "payments" else {
        "spring.security.oauth2.client.registration.payments.client-secret": "oauth_client_secret", "spring.data.redis.password": "redis_password",
        "store.seller.bootstrap-password": "seller_password",
    })
    for target, source in mapping.items():
        shutil.copyfile(secrets_directory / source, destination / target)
        (destination / target).chmod(0o600)
    if database:
        target = destination / "spring.r2dbc.password"
        shutil.copyfile(database.get("runtime_password_file", database["password_file"]), target)
        target.chmod(0o600)
    return destination


@contextmanager
def application(service, jar, directory, secrets_directory, environment):
    log_path = directory / (service + ".log")
    with log_path.open("wb") as log:
        process = subprocess.Popen(["java", *JVM_OPTIONS, "-jar", str(jar), "--server.address=127.0.0.1"],
                                   cwd=directory, env={**os.environ, **environment}, stdout=log, stderr=subprocess.STDOUT)
        try:
            base = "http://127.0.0.1:" + environment["SERVER_PORT"]

            def ready():
                if process.poll() is not None:
                    raise RuntimeError(service + " exited before readiness")
                with urlopen(base + "/actuator/health/readiness", timeout=2) as response:
                    return json.load(response)["status"] == "UP"

            wait_for(ready, service)
            # Build info проверяется в JAR: ради диагностики не публикуем лишний HTTP endpoint.
            with zipfile.ZipFile(jar) as archive:
                build = archive.read("META-INF/build-info.properties").decode()
                assert "build.name=" + service in build
            yield base
        except Exception:
            log.flush()
            diagnostic = log_path.read_text(errors="replace")[-40000:]
            for filename in secrets_directory.iterdir():
                if filename.is_file():
                    value = filename.read_text().strip()
                    if value:
                        diagnostic = diagnostic.replace(value, "[redacted]")
            print(diagnostic, file=sys.stderr)
            raise
        finally:
            if process.poll() is None:
                process.terminate()
            try:
                process.wait(timeout=25)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
                raise RuntimeError(service + " exceeded its graceful shutdown budget")
            if process.returncode not in (0, 143, -15):
                raise RuntimeError(service + " exited with status " + str(process.returncode))


def available_subnet():
    identifiers = subprocess.check_output(["docker", "network", "ls", "-q"], text=True).split()
    networks = json.loads(subprocess.check_output(["docker", "network", "inspect", *identifiers], text=True))
    used = [ipaddress.ip_network(config["Subnet"]) for network in networks
            for config in network["IPAM"].get("Config") or [] if config.get("Subnet")]
    # Сохранённые тестовые сети исчерпывают стандартный пул Docker; /27 хватает всем сервисам проверки.
    for candidate in ipaddress.ip_network("10.250.0.0/16").subnets(new_prefix=27):
        if not any(candidate.version == subnet.version and candidate.overlaps(subnet) for subnet in used):
            return str(candidate)
    raise RuntimeError("No isolated verification subnet is available in 10.250.0.0/16")


@contextmanager
def authorization(secrets_directory):
    name = "sprint08-authorization-test-" + uuid.uuid4().hex
    database_name = name + "-db"
    names = []
    network_created = False
    try:
        subprocess.run(["docker", "compose", "-f", "compose.oauth.yaml", "build", "authorization"],
                       cwd=PROJECT, check=True, stdout=subprocess.DEVNULL)
        subprocess.run(["docker", "network", "create", "--subnet", available_subnet(), "--label", "dev.principalwater.deployment=" + name, name],
                       check=True, stdout=subprocess.DEVNULL)
        network_created = True
        image = configuration()["services"]["authorization-db"]["image"]
        subprocess.run(["docker", "run", "-d", "--name", database_name, "--network", name,
                        "--memory", "256m", "--memory-swap", "256m", "--cpus", "1",
                        "--label", "dev.principalwater.deployment=" + database_name,
                        "--mount", f"type=volume,source={database_name}-data,target=/var/lib/postgresql/data",
                        "-e", "POSTGRES_DB=authorization", "-e", "POSTGRES_USER=authorization",
                        "-e", "POSTGRES_PASSWORD_FILE=/run/secrets/authorization_db_password",
                        "--mount", f"type=bind,source={secrets_directory / 'authorization_db_password'},target=/run/secrets/authorization_db_password,readonly",
                        image], check=True, stdout=subprocess.DEVNULL)
        names.append(database_name)
        wait_for(lambda: subprocess.run(["docker", "exec", database_name, "pg_isready", "-h", "127.0.0.1",
                                        "-U", "authorization", "-d", "authorization"], capture_output=True).returncode == 0,
                 "Authorization PostgreSQL")
        issuer = "http://localhost:18381/realms/storefront-api"
        command = ["docker", "run", "-d", "--name", name, "--network", name, "--user", f"{os.getuid()}:{os.getgid()}",
                   "--memory", "768m", "--memory-swap", "768m", "--cpus", "2", "--read-only",
                   "--tmpfs", "/tmp:rw,noexec,nosuid,size=64m,mode=1777",
                   "--tmpfs", "/opt/keycloak/data:rw,noexec,nosuid,size=32m,mode=1777",
                   "--label", "dev.principalwater.deployment=" + name, "-p", "127.0.0.1::8080",
                   "-e", "KC_BOOTSTRAP_ADMIN_USERNAME=admin", "-e", "KC_HOSTNAME=http://localhost:18381",
                   "-e", "KC_HTTP_ENABLED=true", "-e", "KC_DB=postgres", "-e", "KC_DB_USERNAME=authorization",
                   "-e", "KC_DB_URL=jdbc:postgresql://" + database_name + ":5432/authorization", "-e", "KC_CACHE=local",
                   "-e", "JAVA_OPTS_APPEND=-XX:ActiveProcessorCount=2"]
        for filename in ("authorization_db_password", "authorization_admin_password", "oauth_client_secret"):
            command += ["--mount", f"type=bind,source={secrets_directory / filename},target=/run/secrets/{filename},readonly"]
        command += ["--mount", f"type=bind,source={PROJECT / 'authorization/start-authorization.sh'},target=/setup/start-authorization.sh,readonly",
                    "--mount", f"type=bind,source={PROJECT / 'authorization/storefront-api-realm.json'},target=/opt/keycloak/data/import/storefront-api-realm.json,readonly",
                    "java-sprint08-authorization:local"]
        subprocess.run(command, check=True, stdout=subprocess.DEVNULL)
        names.append(name)
        base = "http://127.0.0.1:" + published_port(name, 8080) + "/realms/storefront-api"
        def ready():
            with urlopen(base + "/.well-known/openid-configuration", timeout=2) as response:
                return json.load(response)["issuer"] == issuer
        wait_for(ready, "Authorization realm", timeout=120)
        yield {"issuer": issuer, "token_uri": base + "/protocol/openid-connect/token",
               "jwk_uri": base + "/protocol/openid-connect/certs", "container": name}
    finally:
        stop_containers(reversed(names))
        if network_created:
            print("Retained authorization network and database: " + name)


@contextmanager
def applications(secrets_directory, redis_endpoint, databases=None):
    with ExitStack() as stack:
        directory = secrets_directory.parent / "jvm"
        directory.mkdir(mode=0o700)
        authorization_endpoints = stack.enter_context(authorization(secrets_directory))
        endpoints = {"token_uri": authorization_endpoints["token_uri"]}
        # Раздельные JVM используют реальные сгенерированные HTTP-клиент и сервер.
        for service, port in [("payments", 18098), ("storefront", 18088)]:
            database = None if databases is None else databases[service]
            tree = configtree(service, secrets_directory, directory / (service + "-config"), database)
            artifact = PROJECT / service / "build/libs" / (service + ".jar")
            if not artifact.is_file():
                raise FileNotFoundError("Package the Gradle executable JAR before verification: " + service)
            environment = {"SERVER_PORT": str(port), "SPRING_PROFILES_ACTIVE": "h2" if database is None else "postgresql",
                           "SPRING_CONFIG_IMPORT": "configtree:" + str(tree) + "/", "STORE_ANALYTICS_ENABLED": "false",
                           "PAYMENTS_OAUTH_ISSUER": authorization_endpoints["issuer"],
                           "PAYMENTS_OAUTH_JWK_SET_URI": authorization_endpoints["jwk_uri"],
                           "PAYMENTS_OAUTH_TOKEN_URI": authorization_endpoints["token_uri"]}
            if database:
                if service == "payments":
                    environment.update(PAYMENTS_DB_URL=database["url"], PAYMENTS_DB_USER=database["runtime_user"],
                                       PAYMENTS_SQL_INIT_MODE="never")
                else:
                    environment.update(DB_URL=database["url"], DB_USER=database["user"])
            if service == "storefront":
                environment.update(STORE_PAYMENTS_URL=endpoints["payments"], SPRING_DATA_REDIS_HOST=redis_endpoint["host"],
                                   SPRING_DATA_REDIS_PORT=redis_endpoint["port"], SPRING_DATA_REDIS_USERNAME="storefront",
                                   STORE_CACHE_NAMESPACE="sprint08-jar-test", STORE_SECRETS_LOCATION=str(tree) + "/")
            endpoints[service] = stack.enter_context(application(service, artifact, directory, secrets_directory, environment))
        yield endpoints
