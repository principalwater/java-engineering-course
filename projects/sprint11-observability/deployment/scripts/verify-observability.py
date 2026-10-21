#!/usr/bin/env python3
"""Конечная проверка наблюдаемости bank11 на живом стенде: Prometheus, Alertmanager, Grafana, Zipkin, журналы, Kafka.

Запускается после verify-runtime.py --analytics --faults: настоящий трафик уже создал пользователей, деньги,
отказы, трассы и журналы, поэтому метрики, спаны и события не подставляются и не имитируются. Все обращения идут
к настоящим HTTP API через kubectl port-forward собственного кластера bank11 (идентичность проверяет
runtime.owned_identity). Пароли читаются из файлов секретов в память и не попадают в argv, URL, вывод и отчёт.

Результат: краткая сводка PASS/SKIP/FAIL и приватный JSON-отчёт во внешнем state-каталоге (без значений секретов).
"""

import argparse
import base64
import contextlib
import http.client
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import select
import subprocess
import sys
import threading
import time
from urllib.parse import quote, urlencode, urlsplit


PROJECT = Path(__file__).resolve().parents[2]

_specification = importlib.util.spec_from_file_location("bank_verify_runtime", Path(__file__).with_name("verify-runtime.py"))
verify = importlib.util.module_from_spec(_specification)
_specification.loader.exec_module(verify)
VerificationError = verify.VerificationError
TransportUnavailable = verify.TransportUnavailable
require = verify.require

HTTP_TIMEOUT = 10
MAX_RESPONSE_BYTES = 16 * 1024 ** 2
FORWARD_START_SECONDS = 30
# Один сбор метрик идёт 15 с, Zipkin отправляет пачки раз в секунду, Elasticsearch обновляет индекс раз в 5 с:
# ожидание ограничено потолком одной проверки и общим бюджетом прогона, остальное время ничего не ждёт.
WAIT_SECONDS = 120
SETTLE_SECONDS = 240
POLL_SECONDS = 2
# Порты и путь целей из chart (global.observability.scrape) и BANK_OBSERVABILITY_METRICS_SERVER_PORT worker-а.
WEB_PORT = 8080
WORKER_PORT = 9400
METRICS_PATH = "/actuator/prometheus"
GRAFANA_HOST = "grafana-bank.valgrind-home.localhost"
BANK_HOST = "bank.valgrind-home.localhost"
# Имена и порты Service chart-а, к которым ходит port-forward.
PORTS = {"prometheus": 9090, "alertmanager": 9093, "grafana": 3000, "zipkin": 9411, "logstash": 9600,
         "elasticsearch": 9200, "kibana": 5601}
ZIPKIN_LOOKBACK_MS = 3_600_000
ZIPKIN_TRACES_PER_SERVICE = 100
LOG_SCAN_EVENTS = 5000
LOG_TRACE_SAMPLE = 30
DASHBOARD_BATCH = 20
# Короче значения не отличить от случайного текста журнала; секреты bank11 — 64 hex-символа.
MIN_NEEDLE_LENGTH = 8
DIAGNOSTIC_LENGTH = 200

LEVELS = frozenset(("TRACE", "DEBUG", "INFO", "WARN", "ERROR"))
SERVICE_NAME = re.compile(r"[a-z0-9][a-z0-9-]{0,39}")
TRACE_ID = re.compile(r"[0-9a-f]{16}(?:[0-9a-f]{16})?")
SPAN_ID = re.compile(r"[0-9a-f]{16}")
# Теги, которыми Logstash помечает отвергнутое значение: событие остаётся, но поле удалено или заменено.
PIPELINE_REJECTIONS = ("invalid_service", "invalid_trace_id", "invalid_span_id", "_jsonparsefailure")
URL_TAGS = ("http.url", "url.full", "http.uri", "http.target", "http.path")
SENSITIVE_QUERY = re.compile(r"[?&](?:access_token|id_token|refresh_token|token|code|state|session_state|password|client_secret)=", re.I)
JWT = re.compile(r"eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]*")
BEARER = re.compile(r"\b(?:bearer|basic)\s+[A-Za-z0-9._~+/=-]{8,}", re.I)
HTTP_SPAN_TAGS = frozenset(("http.url", "url.full", "http.path", "http.method", "uri"))
JDBC_SPAN_NAMES = frozenset(("query", "connection", "result-set"))
# Выражения, которые у здорового стенда законно пусты: ошибки, отказы, потери, DLT, срабатывания и фильтры «> 0».
# Пустой результат такого запроса — нулевое состояние, а не поломка; остальные выражения обязаны вернуть данные.
ZERO_STATE = re.compile(
    r'fail|error|drop|reject|dead|dlt|delayed|corrupt|exceed|missed|offline|underreplicated|shrink|ALERTS\{'
    r'|status(?:_code)?=~"(?:[45]|\[45\])\.\."|exception!="None"', re.I)
# Эти семейства появляются только после соответствующего события; проверочный трафик их не гарантирует.
LAZY_METRICS = ("prometheus_tsdb_compactions_total", "prometheus_notifications_sent_total", "alertmanager_alerts",
                "alertmanager_notifications_total", "grafana_alerting_rule_evaluations_total", "jvm_gc_pause_seconds_",
                "kafka_server_replicafetchermanager_maxlag", "kafka_server_replicamanager_isrexpands_total",
                "ClickHouseProfileEvents_Merge")
# Отказ выдачи наличных гарантирован: verify-runtime.py отклоняет снятие сверх остатка (INSUFFICIENT_FUNDS).
GUARANTEED_FAILURES = ("bank_cash_withdrawal_failures_total",)


# ---------------------------------------------------------------------------------------------------------------
# Отчёт и секреты
# ---------------------------------------------------------------------------------------------------------------

class Report:
    """Печатает итог каждой проверки и копит приватный отчёт; значения секретов вырезаются из любого текста."""

    def __init__(self, needles=()):
        self.needles = sorted({needle for needle in needles if needle}, key=len, reverse=True)
        self.checks = []
        self.facts = {}

    def redact(self, text):
        for needle in self.needles:
            text = text.replace(needle, "[redacted]")
        return text

    def add(self, status, name, detail):
        detail = self.redact(detail)
        self.checks.append({"name": name, "status": status, "detail": detail})
        print(f"{status} {name}: {detail}", file=sys.stderr if status == "FAIL" else sys.stdout, flush=True)

    def passed(self, name, detail):
        self.add("PASS", name, detail)

    def skipped(self, name, reason):
        self.add("SKIP", name, reason)

    def failed(self, name, detail):
        self.add("FAIL", name, detail)

    @contextlib.contextmanager
    def guarded(self, name):
        """Ошибка одного раздела не прячет остальные: она попадает в отчёт без текста внешнего ответа."""
        try:
            yield
        except VerificationError as failure:
            self.failed(name, str(failure)[:DIAGNOSTIC_LENGTH * 4])
        except (OSError, http.client.HTTPException, subprocess.SubprocessError, RuntimeError, ValueError,
                KeyError, TypeError, AttributeError, IndexError) as failure:
            self.failed(name, "Unexpected " + type(failure).__name__)

    def failures(self):
        return [check for check in self.checks if check["status"] == "FAIL"]

    def document(self, cluster):
        text = self.redact(json.dumps({"version": 1, "cluster": cluster, "checks": self.checks, "facts": self.facts},
                                      indent=2, sort_keys=True, default=str))
        return json.loads(text)


def secret_needles(directory, names):
    """Значения секретов для локального поиска утечек; наружу (в Elasticsearch, Zipkin, Prometheus) они не уходят."""
    needles = set()
    for name in names:
        path = Path(directory) / name
        require(path.parent == Path(directory) and path.is_file() and not path.is_symlink()
                and not path.stat().st_mode & 0o077, "Secret source is absent or not private: " + name)
        text = path.read_text(encoding="utf-8", errors="ignore")
        if name.endswith(".pem"):
            # Сертификаты публичны; от закрытого ключа ищем строки тела: кодировщик журнала вырезает целый блок.
            if "PRIVATE KEY" in text:
                body = [line.strip() for line in text.splitlines() if line.strip() and not line.startswith("-----")]
                needles.update(line for line in body if len(line) >= 2 * MIN_NEEDLE_LENGTH)
            continue
        value = text.strip()
        if len(value) >= MIN_NEEDLE_LENGTH and "\n" not in value:
            needles.add(value)
    return sorted(needles)


# ---------------------------------------------------------------------------------------------------------------
# HTTP через port-forward
# ---------------------------------------------------------------------------------------------------------------

@contextlib.contextmanager
def forward(identity, service, remote_port):
    """Тоннель к Service собственного кластера. Механизм bao.connect: он привязан к Pod OpenBao и TLS, поэтому
    повторён здесь для остальных сервисов. Диагностика kubectl отбрасывается: сбой называет только фазу."""
    with subprocess.Popen(["kubectl", "--kubeconfig", identity["kubeconfig"], "--context", identity["context"],
                           "--namespace", identity["namespace"], "port-forward", "--address", "127.0.0.1",
                           f"service/{service}", f":{remote_port}"],
                          stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True) as process:
        drain = None
        try:
            ready = select.select([process.stdout], [], [], FORWARD_START_SECONDS)[0]
            match = re.search(r"127\.0\.0\.1:(\d+) ->", process.stdout.readline() if ready else "")
            if not match:
                raise VerificationError("Port-forward did not start for service " + service)
            # Непрочитанный канал остановил бы kubectl, когда заполнится буфер сообщений о соединениях.
            def discard():
                for _ in process.stdout:
                    pass
            drain = threading.Thread(target=discard, daemon=True)
            drain.start()
            yield int(match.group(1))
        finally:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            if drain:
                drain.join(timeout=5)


class Forwards:
    """Открывает туннель при первом обращении и закрывает все вместе при выходе."""

    def __init__(self, identity):
        self.identity = identity
        self.stack = contextlib.ExitStack()
        self.ports = {}

    def __enter__(self):
        return self

    def __exit__(self, *exception):
        self.stack.close()

    def port(self, service):
        if service not in self.ports:
            self.ports[service] = self.stack.enter_context(forward(self.identity, service, PORTS[service]))
        return self.ports[service]


def basic(user, password):
    return {"Authorization": "Basic " + base64.b64encode(f"{user}:{password}".encode("utf-8")).decode("ascii")}


def call(port, method, path, *, body=None, headers=None):
    payload = json.dumps(body).encode("utf-8") if body is not None else None
    headers = {"Accept": "application/json", **(headers or {})}
    if payload is not None:
        headers["Content-Type"] = "application/json"
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=HTTP_TIMEOUT)
    try:
        connection.request(method, path, payload, headers)
        response = connection.getresponse()
        data = response.read(MAX_RESPONSE_BYTES + 1)
        status = response.status
    except (OSError, http.client.HTTPException):
        raise TransportUnavailable("HTTP transport unavailable") from None
    finally:
        connection.close()
    require(len(data) <= MAX_RESPONSE_BYTES, "HTTP response exceeded verification limit: " + path.split("?")[0])
    return status, data


def get_json(port, path, *, method="GET", body=None, headers=None, expected=(200,)):
    status, data = call(port, method, path, body=body, headers=headers)
    require(status in expected, f"Unexpected status for {method} {path.split('?')[0]}: {status}")
    try:
        return json.loads(data) if data else None
    except (ValueError, UnicodeDecodeError):
        raise VerificationError("Expected JSON from " + path.split("?")[0]) from None


class Stack:
    """Состояние прогона: идентичность кластера, секреты, контур и общий бюджет ожидания."""

    def __init__(self, project, identity, secrets_dir, prepared, forwards, read_secret, needles=()):
        self.project = Path(project)
        self.identity = identity
        self.secrets_dir = Path(secrets_dir)
        self.analytics = bool(prepared.get("analytics"))
        self.telegram = bool(prepared.get("telegram"))
        self.prepared = prepared
        self.forwards = forwards
        self.read_secret = read_secret
        self.needles = list(needles)
        self.deadline = time.monotonic() + SETTLE_SECONDS

    def password(self, name):
        return self.read_secret(self.secrets_dir, name)

    def wait(self, label, check):
        """Опрашивает check() до пустого списка проблем; по истечении срока сообщает последние проблемы."""
        stop = min(time.monotonic() + WAIT_SECONDS, self.deadline)
        while True:
            try:
                problems = check()
            except TransportUnavailable:
                problems = ["endpoint is unreachable"]
            if not problems:
                return
            if time.monotonic() >= stop:
                raise VerificationError(f"{label}: {summarize(problems)}")
            time.sleep(min(POLL_SECONDS, max(0, stop - time.monotonic())))


def summarize(problems, limit=5):
    shown = [problem[:DIAGNOSTIC_LENGTH] for problem in problems[:limit]]
    return "; ".join(shown) + (f"; and {len(problems) - limit} more" if len(problems) > limit else "")


# ---------------------------------------------------------------------------------------------------------------
# Публичные источники истины: файлы проекта, а не копии констант
# ---------------------------------------------------------------------------------------------------------------

def load_yaml(path):
    import yaml

    return yaml.safe_load(Path(path).read_text(encoding="utf-8"))


def expected_services(values, analytics):
    """Сервисы, которые chart превращает в цели Prometheus: web — bank-web, остальные — bank-worker."""
    result = {}
    for name, app in values["apps"].items():
        if app.get("optional") and not analytics:
            continue
        result[name] = "bank-worker" if str(app.get("web", True)).lower() == "false" else "bank-web"
    return result


def public_logins(project):
    """Имена операторов берутся из скриптов начальной настройки: пароли хранятся отдельно, имена не секретны."""
    grafana = (Path(project) / "observability/grafana/grafana-bootstrap.sh").read_text(encoding="utf-8")
    elastic = (Path(project) / "observability/elk/elasticsearch-bootstrap.sh").read_text(encoding="utf-8")
    patterns = {
        "grafana_operator": (grafana, r"^login=([A-Za-z0-9_-]+)$"),
        "grafana_admin": (grafana, r'user = "([A-Za-z0-9_-]+):%s"'),
        "elastic_operator": (elastic, r"^put_user\s+(\S+)\s+elk_operator_password\b"),
    }
    logins = {}
    for key, (text, pattern) in patterns.items():
        match = re.search(pattern, text, re.M)
        require(match is not None, "Bootstrap script does not name the login: " + key)
        logins[key] = match.group(1)
    return logins


def pipeline_ids(project):
    """Идентификаторы плагинов реального конвейера Logstash: по ним видно, что загружен не пример из образа."""
    text = (Path(project) / "observability/elk/bank-logs.conf").read_text(encoding="utf-8")
    return set(re.findall(r'^\s*id\s*=>\s*"([^"]+)"', text, re.M))


# ---------------------------------------------------------------------------------------------------------------
# Prometheus
# ---------------------------------------------------------------------------------------------------------------

def target_problems(targets, jobs, services, analytics):
    """Сверяет активные цели с prometheus.yml и составом включённых сервисов, включая worker на порту 9400."""
    by_job = {}
    for target in targets:
        by_job.setdefault(target["labels"].get("job"), []).append(target)
    problems = []
    for job in jobs:
        items = by_job.get(job, [])
        if job == "clickhouse" and not analytics:
            if items:
                problems.append("clickhouse is scraped although analytics is disabled")
            continue
        if not items:
            problems.append(f"job {job} has no active target")
        for target in items:
            if target.get("health") != "up":
                name = target["labels"].get("service") or target["labels"].get("instance")
                problems.append(f"{job} target {name} is {target.get('health')}")
    for job, port in (("bank-web", WEB_PORT), ("bank-worker", WORKER_PORT)):
        found = {target["labels"].get("service"): target for target in by_job.get(job, [])}
        wanted = {name for name, owner in services.items() if owner == job}
        if wanted - found.keys():
            problems.append(f"{job} lacks targets for {sorted(wanted - found.keys())}")
        if found.keys() - wanted:
            problems.append(f"{job} scrapes unexpected services {sorted(found.keys() - wanted, key=str)}")
        for service, target in found.items():
            url = urlsplit(target.get("scrapeUrl", ""))
            if url.port != port or url.path != METRICS_PATH:
                problems.append(f"{service} metrics endpoint is port {url.port} path {url.path}, expected {port}{METRICS_PATH}")
    return problems


def instant(port, expression):
    data = get_json(port, "/api/v1/query?" + urlencode({"query": expression}))
    require(data.get("status") == "success", "Prometheus rejected a verification query")
    rows = [(item["metric"], float(item["value"][1])) for item in data["data"]["result"]]
    require(all(math.isfinite(value) for _, value in rows), "Prometheus returned a non-finite verification value")
    return rows


def by_service(port, expression):
    return {metric.get("service", ""): value for metric, value in instant(port, expression)}


def total(port, expression):
    return sum(value for _, value in instant(port, expression))


def lacking(wanted, found, what):
    missing = sorted(set(wanted) - set(found))
    return [f"{what} is absent for {missing}"] if missing else []


def check_prometheus(stack, report):
    port = stack.forwards.port("prometheus")
    jobs = [item["job_name"] for item in load_yaml(stack.project / "observability/prometheus/prometheus.yml")["scrape_configs"]]
    values = load_yaml(stack.project / "deployment/charts/bank/values.yaml")
    services = expected_services(values, stack.analytics)
    apps = values["apps"]

    def targets():
        found = get_json(port, "/api/v1/targets?state=active")["data"]["activeTargets"]
        report.facts["targets"] = sorted(
            f"{item['labels'].get('job')}/{item['labels'].get('service') or item['labels'].get('instance')} "
            f"{urlsplit(item.get('scrapeUrl', '')).netloc.rsplit(':', 1)[-1]}{urlsplit(item.get('scrapeUrl', '')).path} {item.get('health')}"
            for item in found)
        return target_problems(found, jobs, services, stack.analytics)

    with report.guarded("prometheus targets"):
        stack.wait("Prometheus targets", targets)
        workers = sorted(name for name, job in services.items() if job == "bank-worker")
        report.passed("prometheus targets", f"{len(services)} Java services up on {METRICS_PATH} "
                      f"(web port {WEB_PORT}; worker {workers} port {WORKER_PORT}); every configured platform job is up")

    with report.guarded("jvm and pool metrics"):
        pooled = {name for name, app in apps.items() if name in services and app.get("jdbc")}
        def jvm_metrics():
            heap = by_service(port, 'sum by (service) (jvm_memory_used_bytes{job=~"bank-(web|worker)", area="heap"})')
            pool = by_service(port, 'count by (service) (hikaricp_connections_active{job=~"bank-(web|worker)"})')
            return lacking(services, heap, "jvm_memory_used_bytes") + lacking(pooled, pool, "hikaricp_connections_active") + [
                f"{name} has no used JVM heap" for name, value in heap.items() if value <= 0]
        stack.wait("JVM and connection pool series", jvm_metrics)
        report.passed("jvm and pool metrics", f"JVM heap series for {sorted(services)}; Hikari pools for {sorted(pooled)}")

    with report.guarded("http metrics"):
        web = {name for name, job in services.items() if job == "bank-web"}
        def http_metrics():
            found = by_service(port, 'sum by (service) (http_server_requests_seconds_count{job="bank-web", uri!~"/actuator.*"})')
            return lacking(web, found, "http_server_requests_seconds_count") + [
                f"{name} served no application request" for name in sorted(web & found.keys()) if found[name] <= 0]
        stack.wait("HTTP server series", http_metrics)
        report.passed("http metrics", f"non-actuator requests counted for {sorted(web)}")

    with report.guarded("kafka metrics"):
        clients = {name for name, app in apps.items() if name in services and app.get("kafka")}
        def kafka_metrics():
            found = by_service(port, 'count by (service) ({__name__=~"kafka_(producer|consumer)_.+", job=~"bank-(web|worker)"})')
            problems = lacking(clients, found, "kafka client metrics")
            if total(port, "sum(kafka_server_brokertopicmetrics_messagesin_total)") <= 0:
                problems.append("brokers report no incoming messages")
            if not instant(port, "kafka_consumergroup_lag"):
                problems.append("Kafka Exporter reports no consumer group lag")
            return problems
        stack.wait("Kafka series", kafka_metrics)
        report.passed("kafka metrics", f"client series for {sorted(clients)}; broker traffic and consumer lag series present")

    if stack.analytics:
        with report.guarded("clickhouse metrics"):
            def clickhouse_metrics():
                return [f"{event} is empty or zero" for event in ("Query", "InsertedRows")
                        if total(port, f"sum(ClickHouseProfileEvents_{event})") <= 0]
            stack.wait("ClickHouse series", clickhouse_metrics)
            report.passed("clickhouse metrics", "native endpoint reports executed queries and inserted rows")
    else:
        report.skipped("clickhouse metrics", "analytics contour is disabled; ClickHouse is not deployed or scraped")

    with report.guarded("financial failure metrics"):
        rows = []
        def withdrawals():
            rows[:] = instant(port, "bank_cash_withdrawal_failures_total")
            return [] if any(m.get("login") and m.get("reason") and value >= 1 for m, value in rows) else [
                "no labelled committed withdrawal rejection"]
        stack.wait("Withdrawal failure counter", withdrawals)
        report.passed("financial failure metrics", "withdrawal rejections are counted by login and reason")
        for metric, labels in (("bank_transfer_failures_total", ("sender", "recipient", "reason")),
                               ("bank_notification_failures_total", ("login", "reason"))):
            found = instant(port, metric)
            if not found:
                report.skipped("financial failure metrics", f"{metric} has no series: no such failure occurred, zero-state is valid")
                continue
            require(all(all(m.get(label) for label in labels) for m, _ in found), f"{metric} lacks one of the labels {labels}")
            report.passed("financial failure metrics", f"{metric} carries labels {labels}")

    with report.guarded("prometheus integrity"):
        require(total(port, "sum(prometheus_target_scrapes_exceeded_sample_limit_total)") == 0,
                "A scrape target exceeded its sample limit (cardinality guard)")
        require(total(port, "min(prometheus_config_last_reload_successful)") == 1, "Prometheus rejected its configuration")
        report.passed("prometheus integrity", "no scrape dropped by sample limits; configuration reload succeeded")


def check_rules(stack, report):
    port = stack.forwards.port("prometheus")
    with report.guarded("alert rules"):
        expected = set()
        for path in sorted((stack.project / "observability/prometheus").glob("*.rules.yml")):
            expected.update(rule["alert"] for group in load_yaml(path)["groups"] for rule in group["rules"] if "alert" in rule)
        def loaded():
            groups = get_json(port, "/api/v1/rules?type=alert")["data"]["groups"]
            rules = [rule for group in groups for rule in group["rules"]]
            unhealthy = sorted(rule["name"] for rule in rules if rule.get("health") != "ok")
            return lacking(expected, {rule["name"] for rule in rules}, "alert rule") + (
                [f"rules are not healthy: {unhealthy}"] if unhealthy else [])
        stack.wait("Prometheus alert rules", loaded)
        report.passed("alert rules", f"{len(expected)} alert rules from the repository are loaded and evaluate without errors")


# ---------------------------------------------------------------------------------------------------------------
# Alertmanager
# ---------------------------------------------------------------------------------------------------------------

def check_alertmanager(stack, report):
    import yaml

    port = stack.forwards.port("alertmanager")
    with report.guarded("alertmanager"):
        require(call(port, "GET", "/-/ready")[0] == 200, "Alertmanager is not ready")
        status = get_json(port, "/api/v2/status")
        config = yaml.safe_load(status["config"]["original"])
        receivers = {item["name"] for item in config["receivers"]}
        require(config["route"]["receiver"] in receivers, "Alertmanager route points to an unknown receiver")
        telegram = {item["name"] for item in load_yaml(stack.project / "observability/prometheus/alertmanager.yml")["receivers"]}
        if stack.telegram:
            require(telegram <= receivers, "Alertmanager lacks the configured Telegram receivers")
            detail = f"configuration is valid with receivers {sorted(receivers)}; no notification was sent"
        else:
            require(not telegram & receivers, "Alertmanager references Telegram although it is disabled")
            detail = f"configuration is valid with local receivers {sorted(receivers)}; Telegram is disabled"
        linked = get_json(stack.forwards.port("prometheus"), "/api/v1/alertmanagers")["data"]["activeAlertmanagers"]
        require(any(urlsplit(item["url"]).hostname == "alertmanager" for item in linked), "Prometheus has no active Alertmanager")
        report.passed("alertmanager", detail + "; Prometheus delivers to it")


# ---------------------------------------------------------------------------------------------------------------
# Zipkin
# ---------------------------------------------------------------------------------------------------------------

def span_kinds(span):
    tags = span.get("tags") or {}
    kinds = set()
    if span.get("kind") in ("SERVER", "CLIENT") and HTTP_SPAN_TAGS & tags.keys():
        kinds.add("http-server" if span["kind"] == "SERVER" else "http-client")
    if span.get("name") in JDBC_SPAN_NAMES or any(key.startswith("jdbc.") for key in tags):
        kinds.add("jdbc")
    if span.get("kind") in ("PRODUCER", "CONSUMER"):
        kinds.add("kafka-" + span["kind"].lower())
    return kinds


def leaks_in(text, needles):
    """Названия классов утечки в строке; сами значения никогда не возвращаются."""
    found = []
    if "?" in text and SENSITIVE_QUERY.search(text):
        found.append("sensitive query parameter")
    if JWT.search(text):
        found.append("JWT")
    if BEARER.search(text):
        found.append("authorization scheme value")
    if any(needle in text for needle in needles):
        found.append("known secret value")
    return found


def analyze_traces(traces, services, needles):
    """Разбирает трассы Zipkin v2: HTTP, JDBC, Kafka, родительские связи и приватность. Возвращает (факты, проблемы)."""
    counts = {"http-server": 0, "http-client": 0, "jdbc": 0, "kafka-producer": 0, "kafka-consumer": 0}
    seen, leaks, chains, best = set(), {}, [], (-1, "none")
    for trace in traces:
        if not trace:
            continue
        identifiers = {span["id"] for span in trace}
        valid_ids = all(isinstance(span.get("traceId"), str) and TRACE_ID.fullmatch(span["traceId"])
                        and int(span["traceId"], 16) != 0 and span["traceId"] == trace[0]["traceId"]
                        and isinstance(span.get("id"), str) and SPAN_ID.fullmatch(span["id"])
                        and int(span["id"], 16) != 0 for span in trace)
        features = set()
        orphans = sum(1 for span in trace if span.get("parentId") and span["parentId"] not in identifiers)
        producers = {span["id"] for span in trace if "kafka-producer" in span_kinds(span)}
        for span in trace:
            seen.add((span.get("localEndpoint") or {}).get("serviceName"))
            kinds = span_kinds(span)
            for kind in kinds:
                counts[kind] += 1
            if "jdbc" in kinds and span.get("parentId") in identifiers:
                features.add("jdbc")
            if "http-server" in kinds:
                features.add("http")
            if "kafka-consumer" in kinds and span.get("parentId") in producers:
                features.add("kafka")
            fields = {"name": span.get("name", ""), **(span.get("tags") or {})}
            for key, value in fields.items():
                text = str(value)
                kinds_found = leaks_in(text, needles)
                if key in URL_TAGS and "?" in text:
                    kinds_found.append("query string in URL tag")
                if key.startswith("jdbc.params"):
                    kinds_found.append("JDBC parameter values")
                for found in kinds_found:
                    leaks[(key, found)] = leaks.get((key, found), 0) + 1
        roots = sum(1 for span in trace if not span.get("parentId"))
        reachable = {span["id"] for span in trace if not span.get("parentId")}
        while True:
            children = {span["id"] for span in trace if span.get("parentId") in reachable}
            if children <= reachable:
                break
            reachable.update(children)
        if (features == {"http", "jdbc", "kafka"} and valid_ids and orphans == 0
                and roots == 1 and reachable == identifiers and complete_bank_chain(trace)):
            chains.append(trace[0]["traceId"])
        elif len(features) > best[0]:
            best = (len(features), f"{sorted(features)} orphans={orphans} roots={roots}")
    problems = [f"no spans from {sorted(services - seen)}"] if services - seen else []
    if not chains:
        problems.append("no complete trace links front -> gateway -> cash/transfer -> accounts, an Accounts JDBC query "
                        "and a Kafka producer to its consumer "
                        f"with consistent parent ids (best partial trace: {best[1]}; {len(traces)} traces read)")
    facts = {"traces": len(traces), "spans": sum(len(trace) for trace in traces), "kinds": counts, "chains": len(chains), "chain_examples": chains[:5],
             "services": sorted(name for name in seen if name)}
    facts["leaks"] = [f"{key}: {found} x{count}" for (key, found), count in sorted(leaks.items())]
    return facts, problems


def complete_bank_chain(trace):
    """Одних типов span недостаточно: запрос должен пройти банковские сервисы по порядку."""
    parents = {}
    servers = {}
    producers = {span["id"] for span in trace if "kafka-producer" in span_kinds(span)}
    for span in trace:
        parents.setdefault(span["id"], set()).update([span["parentId"]] if span.get("parentId") else [])
        if "http-server" in span_kinds(span):
            service = (span.get("localEndpoint") or {}).get("serviceName")
            servers.setdefault(service, set()).add(span["id"])

    def ancestors(identifier):
        found, pending = set(), list(parents.get(identifier, ()))
        while pending:
            parent = pending.pop()
            if parent not in found:
                found.add(parent)
                pending.extend(parents.get(parent, ()))
        return found

    money = servers.get("cash", set()) | servers.get("transfer", set())
    for account in servers.get("accounts", ()):
        branches = {node for node in money & ancestors(account)
                    if any(gateway in ancestors(node) and servers.get("front", set()) & ancestors(gateway)
                           for gateway in servers.get("gateway", ()))}
        jdbc = any("jdbc" in span_kinds(span) and account in ancestors(span["id"]) for span in trace)
        kafka = any("kafka-consumer" in span_kinds(span) and span.get("parentId") in producers
                    and branches & ancestors(span["parentId"]) for span in trace)
        if branches and jdbc and kafka:
            return True
    return False


def fetch_traces(port, services):
    traces = {}
    for service in sorted(services):
        batch = get_json(port, "/api/v2/traces?" + urlencode({"serviceName": service, "limit": ZIPKIN_TRACES_PER_SERVICE,
                                                              "lookback": ZIPKIN_LOOKBACK_MS}))
        for trace in batch:
            if trace:
                traces[trace[0]["traceId"]] = trace
    return list(traces.values())


def check_zipkin(stack, report):
    port = stack.forwards.port("zipkin")
    values = load_yaml(stack.project / "deployment/charts/bank/values.yaml")
    services = set(expected_services(values, stack.analytics))
    with report.guarded("zipkin"):
        require(get_json(port, "/health").get("status") == "UP", "Zipkin health is not UP")
        latest = {}
        def complete():
            facts, problems = analyze_traces(fetch_traces(port, services), services, stack.needles)
            latest.update(facts)
            # Утечка не исчезнет с ожиданием: она сообщается сразу, не дожидаясь полноты трасс.
            if facts["leaks"]:
                raise VerificationError("Zipkin spans leak sensitive data: " + summarize(facts["leaks"]))
            return problems
        stack.wait("Zipkin traces", complete)
        report.facts["zipkin"] = latest
        report.passed("zipkin", f"{latest['spans']} spans in {latest['traces']} traces from {latest['services']}; "
                      "front -> gateway -> cash/transfer -> accounts, Accounts JDBC and Kafka delivery "
                      f"linked by parent ids in {latest['chains']} trace(s); "
                      "no URL query strings, tokens or secret values in names or tags")


# ---------------------------------------------------------------------------------------------------------------
# Журналы: Logstash и Elasticsearch
# ---------------------------------------------------------------------------------------------------------------

def analyze_logs(documents, services, needles):
    """Разбирает проиндексированные события. Возвращает (факты, дефекты, пробелы).

    Дефект — искажённое событие или утечка секрета: ожидание его не исправит. Пробел — события, которые ещё
    не дошли до индекса (нет сервиса или трассировочных полей): его закрывает повторный опрос.
    """
    defects, per_service, traced = {}, {}, 0

    def flag(message):
        defects[message] = defects.get(message, 0) + 1

    for document in documents:
        service = document.get("service")
        per_service[service] = per_service.get(service, 0) + 1
        if not isinstance(service, str) or not SERVICE_NAME.fullmatch(service) or service in ("unknown", "invalid"):
            flag("event without a valid service name")
        if document.get("level") not in LEVELS:
            flag("event with an unknown level")
        for field in ("event", "logger_name", "@timestamp"):
            if not isinstance(document.get(field), str) or not document[field]:
                flag(f"event without {field}")
        trace, span = document.get("traceId"), document.get("spanId")
        if trace is not None or span is not None:
            if isinstance(trace, str) and TRACE_ID.fullmatch(trace) and isinstance(span, str) and SPAN_ID.fullmatch(span):
                traced += 1
            else:
                flag("event with a malformed trace or span id")
        rejected = [tag for tag in document.get("tags") or [] if tag in PIPELINE_REJECTIONS]
        if rejected:
            flag(f"pipeline rejected a field: {rejected}")
        for found in leaks_in(json.dumps(document, ensure_ascii=False), needles):
            flag(f"log event leaks a {found}")
    gaps = []
    if services - per_service.keys():
        gaps.append(f"no log events from {sorted(services - per_service.keys())}")
    if documents and not traced:
        gaps.append("no event carries traceId and spanId")
    facts = {"events": len(documents), "traced": traced,
             "per_service": {str(name): count for name, count in sorted(per_service.items(), key=str)}}
    return facts, [f"{message} (x{count})" for message, count in sorted(defects.items())], gaps


def correlated_logs(port, documents):
    """Считает реальные совпадения traceId/spanId; Zipkin может вернуть HTTP 200 с пустым массивом."""
    pairs = sorted({(doc["traceId"], doc["spanId"]) for doc in documents if doc.get("traceId") and doc.get("spanId")})[:LOG_TRACE_SAMPLE]
    traces, matches = {}, 0
    for trace_id, span_id in pairs:
        if trace_id not in traces:
            status, payload = call(port, "GET", "/api/v2/trace/" + quote(trace_id))
            require(status in (200, 404), f"Zipkin trace lookup returned HTTP {status}")
            traces[trace_id] = json.loads(payload) if status == 200 else []
            require(isinstance(traces[trace_id], list), "Zipkin trace lookup did not return spans")
        if any(span.get("traceId") == trace_id and span.get("id") == span_id for span in traces[trace_id]):
            matches += 1
    return matches


def check_logs(stack, report):
    values = load_yaml(stack.project / "deployment/charts/bank/values.yaml")
    services = set(expected_services(values, stack.analytics))
    login = public_logins(stack.project)["elastic_operator"]
    auth = basic(login, stack.password("elk_operator_password"))
    port = stack.forwards.port("elasticsearch")
    with report.guarded("logstash pipeline"):
        pipeline = stack.forwards.port("logstash")
        wanted = pipeline_ids(stack.project)
        def running():
            stats = get_json(pipeline, "/_node/stats/pipelines")["pipelines"].get("main")
            if stats is None:
                return ["pipeline main is not loaded"]
            plugins = stats["plugins"]
            loaded = {item["id"] for kind in ("inputs", "filters", "outputs") for item in plugins.get(kind, [])}
            problems = lacking(wanted, loaded, "pipeline plugin id")
            if stats["events"]["out"] <= 0:
                problems.append("pipeline has not emitted an event yet")
            failed = sum(item.get("documents", {}).get("non_retryable_failures", 0) for item in plugins.get("outputs", []))
            return problems + ([f"Elasticsearch rejected {failed} documents"] if failed else [])
        stack.wait("Logstash pipeline", running)
        report.passed("logstash pipeline", f"the repository pipeline main runs ({len(wanted)} plugin ids) and delivers events without rejected documents")

    with report.guarded("elasticsearch logs"):
        denied = call(port, "POST", "/bank11-logs-*/_search", body={"size": 0})[0]
        require(denied == 401, f"Elasticsearch served an anonymous search (HTTP {denied})")
        found = {}
        def indexed():
            body = {"size": LOG_SCAN_EVENTS, "track_total_hits": True, "sort": [{"@timestamp": "desc"}]}
            result = get_json(port, "/bank11-logs-*/_search", method="POST", body=body, headers=auth)["hits"]
            documents = [hit["_source"] for hit in result["hits"]]
            facts, defects, gaps = analyze_logs(documents, services, stack.needles)
            found.update(facts, total=result["total"]["value"], documents=documents)
            if defects:
                raise VerificationError("Elasticsearch events are malformed or leak data: " + summarize(defects))
            return gaps
        stack.wait("Elasticsearch log events", indexed)
        documents = found.pop("documents")
        report.facts["logs"] = found
        scanned = f"{found['events']} of {found['total']} newest events"
        report.passed("elasticsearch logs", f"{scanned} carry service, level, event; {found['traced']} with traceId and spanId; "
                      f"all services logging; no known secret values")

        zipkin = stack.forwards.port("zipkin")
        hits = []
        def correlated():
            hits[:] = [correlated_logs(zipkin, documents)]
            return [] if hits[0] else ["no sampled log event matches a real Zipkin traceId/spanId"]
        stack.wait("Log trace correlation", correlated)
        report.passed("log trace correlation", f"{hits[0]} sampled log traceId/spanId pairs match actual Zipkin spans")


# ---------------------------------------------------------------------------------------------------------------
# Grafana
# ---------------------------------------------------------------------------------------------------------------

def walk_panels(panels):
    for panel in panels:
        yield panel
        yield from walk_panels(panel.get("panels", []))


def panel_signature(dashboard):
    return sorted((panel.get("id"), panel.get("title"), json.dumps(panel.get("targets", []), sort_keys=True),
                   json.dumps(panel.get("datasource"), sort_keys=True)) for panel in walk_panels(dashboard.get("panels", [])))


def variable_values(dashboard):
    """Значения переменных панели «All»; текстовое поле поиска по журналу заменяется на match-all."""
    values = {}
    for item in dashboard.get("templating", {}).get("list", []):
        if item.get("type") == "textbox":
            # По умолчанию там level:ERROR — нулевое состояние; match-all показывает, что диаграммы рисуют настоящие события.
            values[item["name"]] = "*"
        elif item.get("includeAll") and item.get("allValue"):
            values[item["name"]] = item["allValue"]
        else:
            raise VerificationError("Dashboard variable has no verifiable value: " + item.get("name", "?"))
    return values


def expand_variables(expression, values):
    """Подставляет переменные панели; встроенные макросы Grafana ($__range, $__rate_interval) оставляет серверу."""
    def replace(match):
        name = match.group(1) or match.group(2)
        if name.startswith("__"):
            return match.group(0)
        require(name in values, "Dashboard query uses an unknown variable: " + name)
        return values[name]
    return re.sub(r"\$\{(\w+)\}|\$(\w+)", replace, expression)


def classify(expression, datasource, analytics):
    """core — данные обязательны; zero-state — пусто законно без сбоев; optional — контур не развёрнут."""
    if datasource == "bank-logs":
        return "zero-state" if re.search(r'level\s*:\s*(?:ERROR|WARN)', expression, re.I) else "core"
    if not analytics and re.search(r'job="clickhouse"|ClickHouse', expression):
        return "optional"
    if any(metric in expression for metric in GUARANTEED_FAILURES):
        return "core"
    return "zero-state" if ZERO_STATE.search(expression) or any(metric in expression for metric in LAZY_METRICS) else "core"


def chart_queries(dashboard, analytics):
    """Запросы всех панелей в виде тела /api/ds/query; ссылка refId возвращает панель и класс запроса."""
    values = variable_values(dashboard)
    queries, owners = [], {}
    for panel in walk_panels(dashboard.get("panels", [])):
        for target in panel.get("targets") or []:
            datasource = target.get("datasource") or panel.get("datasource") or {}
            uid = datasource.get("uid")
            require(datasource.get("type") in ("prometheus", "elasticsearch") and uid in ("bank-prometheus", "bank-logs"),
                    f"Panel {panel.get('title')!r} uses an unverifiable datasource")
            ref = f"q{len(queries)}"
            if datasource["type"] == "prometheus":
                expression = expand_variables(target["expr"], values)
                query = {**target, "expr": expression}
            else:
                expression = expand_variables(target.get("query", ""), values)
                query = {**target, "query": expression}
            query.update(refId=ref, datasource={"type": datasource["type"], "uid": uid}, intervalMs=15000, maxDataPoints=500)
            queries.append(query)
            owners[ref] = {"panel": panel.get("title"), "class": classify(expression, uid, analytics)}
    return queries, owners


def frame_rows(result):
    rows = 0
    for frame in result.get("frames") or []:
        columns = (frame.get("data") or {}).get("values") or []
        fields = (frame.get("schema") or {}).get("fields") or []
        for field, column in zip(fields, columns):
            if field.get("type") == "number":
                rows += sum(isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) for value in column)
            elif field.get("type") == "string":
                rows += sum(isinstance(value, str) and bool(value) for value in column)
    return rows


def query_dashboard(port, auth, dashboard, analytics):
    """Выполняет запросы диаграмм через Grafana и делит итог на ошибки, пустые обязательные и нулевые состояния."""
    queries, owners = chart_queries(dashboard, analytics)
    outcome = {"queries": len(queries), "answered": 0, "errors": [], "empty_core": [], "zero_state": [], "optional": []}
    for start in range(0, len(queries), DASHBOARD_BATCH):
        batch = queries[start:start + DASHBOARD_BATCH]
        body = {"queries": batch, "from": "now-1h", "to": "now"}
        reply = get_json(port, "/api/ds/query", method="POST", body=body, headers=auth, expected=(200, 207, 400))
        require(isinstance(reply, dict) and isinstance(reply.get("results"), dict), "Grafana returned no query results")
        for query in batch:
            result, owner = reply["results"].get(query["refId"]), owners[query["refId"]]
            if not isinstance(result, dict) or result.get("error") or result.get("status", 200) != 200:
                # Текст ошибки datasource может содержать возвращённые сервером credentials.
                outcome["errors"].append(f"{owner['panel']}: query failed or result missing")
            elif frame_rows(result) > 0:
                outcome["answered"] += 1
            else:
                outcome[{"core": "empty_core", "zero-state": "zero_state", "optional": "optional"}[owner["class"]]].append(owner["panel"])
    return outcome


def check_grafana(stack, report):
    port = stack.forwards.port("grafana")
    logins = public_logins(stack.project)
    operator = basic(logins["grafana_operator"], stack.password("grafana_operator_password"))
    folder = load_yaml(stack.project / "observability/grafana/dashboards.yaml")["providers"][0]["folder"]
    expected = {}
    for path in sorted((stack.project / "observability/grafana").glob("bank-*.json")):
        document = json.loads(path.read_text(encoding="utf-8"))
        expected[document["uid"]] = document

    with report.guarded("grafana access"):
        health = get_json(port, "/api/health")
        require(health.get("database") == "ok", "Grafana database is not healthy")
        for path in ("/api/search", "/api/datasources", "/api/user"):
            status = call(port, "GET", path)[0]
            require(status == 401, f"Grafana served {path} without credentials (HTTP {status})")
        status = call(port, "POST", "/api/ds/query", body={"queries": [], "from": "now-5m", "to": "now"})[0]
        require(status == 401, f"Grafana ran a query without credentials (HTTP {status})")
        user = get_json(port, "/api/user", headers=operator)
        require(user.get("login") == logins["grafana_operator"] and not user.get("isGrafanaAdmin"), "Operator identity is wrong")
        roles = {item["role"] for item in get_json(port, "/api/user/orgs", headers=operator)}
        require(roles == {"Viewer"}, "Grafana operator is not a pure Viewer")
        status = call(port, "POST", "/api/dashboards/db", body={}, headers=operator)[0]
        require(status == 403, f"Grafana operator can write dashboards (HTTP {status})")
        report.passed("grafana access", f"anonymous API calls are refused; {logins['grafana_operator']} signs in as read-only Viewer")

    with report.guarded("grafana datasources"):
        admin = basic(logins["grafana_admin"], stack.password("grafana_admin_password"))
        listed = {item["uid"]: item for item in get_json(port, "/api/datasources", headers=admin)}
        provisioned = load_yaml(stack.project / "observability/grafana/datasources.yaml")["datasources"]
        for source in provisioned:
            item = listed.get(source["uid"])
            require(item is not None, "Datasource is not provisioned: " + source["uid"])
            require(item["type"] == source["type"] and item["url"] == source["url"] and item.get("readOnly"),
                    "Datasource differs from its provisioning file or is editable: " + source["uid"])
            uid = quote(source["uid"])
            if source["type"] in ("prometheus", "elasticsearch"):
                health = get_json(port, f"/api/datasources/uid/{uid}/health", headers=admin)
                require(str(health.get("status", "")).upper() == "OK", "Datasource health check failed: " + source["uid"])
            else:
                path = "/api/v2/services" if source["type"] == "zipkin" else "/api/v2/status"
                get_json(port, f"/api/datasources/proxy/uid/{uid}{path}", headers=admin)
        report.passed("grafana datasources", f"{len(provisioned)} provisioned read-only datasources are healthy and reachable")

    served = {}
    with report.guarded("grafana dashboards"):
        found = {item["uid"]: item for item in get_json(port, "/api/search?type=dash-db&limit=5000", headers=operator)}
        for uid, document in expected.items():
            require(uid in found, "Dashboard is not provisioned: " + uid)
            reply = get_json(port, "/api/dashboards/uid/" + quote(uid), headers=operator)
            require(reply["meta"].get("provisioned") and reply["meta"].get("folderTitle") == folder,
                    f"Dashboard {uid} is not file-provisioned into folder {folder}")
            require(panel_signature(reply["dashboard"]) == panel_signature(document), f"Dashboard {uid} differs from its provisioning file")
            served[uid] = reply["dashboard"]
        report.passed("grafana dashboards", f"{len(served)} dashboards from the repository are provisioned into {folder} with their panels")

    with report.guarded("grafana charts"):
        require(set(served) == set(expected), "Chart verification requires every provisioned dashboard")
        prometheus = stack.forwards.port("prometheus")
        answered = queries = 0
        errors, empty, zero, optional = [], [], [], []
        for uid, dashboard in sorted(served.items()):
            outcome = query_dashboard(port, operator, dashboard, stack.analytics)
            answered, queries = answered + outcome["answered"], queries + outcome["queries"]
            errors += [f"{uid}/{error}" for error in outcome["errors"]]
            empty += [f"{uid}/{panel}" for panel in outcome["empty_core"]]
            zero += [f"{uid}/{panel}" for panel in outcome["zero_state"]]
            optional += [f"{uid}/{panel}" for panel in outcome["optional"]]
            for item in dashboard.get("templating", {}).get("list", []):
                if item.get("type") == "query":
                    selector = re.fullmatch(r"label_values\((?:(.*),\s*)?(\w+)\)", item["definition"])
                    require(selector is not None, "Unsupported dashboard variable query: " + item["name"])
                    params = {"match[]": selector.group(1)} if selector.group(1) else {}
                    if not get_json(prometheus, f"/api/v1/label/{selector.group(2)}/values?" + urlencode(params))["data"]:
                        empty.append(f"{uid}/variable {item['name']}")
        report.facts["charts"] = {"queries": queries, "answered": answered, "errors": errors, "empty_core": empty,
                                  "zero_state": zero, "optional": optional}
        problems = ([f"{len(errors)} chart queries failed ({summarize(errors, 3)})"] if errors else []) + (
            [f"{len(empty)} required charts returned no data ({summarize(empty, 3)})"] if empty else [])
        require(not problems, "Grafana charts: " + "; ".join(problems))
        report.passed("grafana charts", f"{answered} of {queries} chart queries and all variable lists returned data through Grafana")
        if zero:
            report.skipped("grafana charts", f"{len(zero)} event-dependent queries are empty; zero-state is valid")
        if optional:
            report.skipped("grafana charts", f"{len(optional)} queries belong to the disabled analytics contour")


def check_kibana(stack, report):
    with report.guarded("kibana"):
        login = public_logins(stack.project)["elastic_operator"]
        auth = basic(login, stack.password("elk_operator_password"))
        port = stack.forwards.port("kibana")
        view = json.loads((stack.project / "observability/elk/kibana-data-view.json").read_text(encoding="utf-8"))["data_view"]
        def available():
            status = get_json(port, "/api/status", headers=auth)
            return [] if status["status"]["overall"]["level"] == "available" else ["Kibana is not available"]
        stack.wait("Kibana status", available)
        reply = get_json(port, "/api/data_views/data_view/" + quote(view["id"]), headers=auth)
        require(reply["data_view"]["title"] == view["title"], "Kibana data view does not match its definition")
        report.passed("kibana", f"available; data view {view['title']} is readable by the log operator")


# ---------------------------------------------------------------------------------------------------------------
# Edge и Kafka
# ---------------------------------------------------------------------------------------------------------------

def check_edge(stack, report):
    with report.guarded("edge"):
        verify.HOSTS = frozenset(verify.HOSTS | {GRAFANA_HOST})
        base = f"https://{{}}:{stack.identity['external_port']}"
        try:
            bank = verify.User(base.format(BANK_HOST), stack.secrets_dir)
            for path in ("/actuator", "/actuator/prometheus", "/actuator/metrics", "/actuator/health/liveness"):
                status = bank.request("GET", path)[0]
                require(status == 404, f"Edge exposed {path} on the bank host (HTTP {status})")
            grafana = verify.User(base.format(GRAFANA_HOST), stack.secrets_dir)
            # Эти пути закрывает Caddy ещё до проверки учётной записи Grafana.
            for path in ("/metrics", "/api/admin/settings"):
                status = grafana.request("GET", path)[0]
                require(status == 404, f"Edge exposed Grafana {path} (HTTP {status})")
            for method, path in (("GET", "/api/search"), ("GET", "/api/datasources"), ("POST", "/api/ds/query")):
                status = grafana.request(method, path, {"queries": []} if method == "POST" else None, csrf=False)[0]
                require(status == 401, f"Edge served Grafana {path} without credentials (HTTP {status})")
            status, final, _ = grafana.request("GET", "/")
        except TransportUnavailable:
            raise VerificationError(f"Edge is unreachable over trusted TLS; does the leaf certificate cover {GRAFANA_HOST}?") from None
        require(status == 200 and urlsplit(final).path == "/login", "Anonymous Grafana visitors are not sent to the login page")
        report.passed("edge", "actuator and Grafana internals are blocked; Grafana API refuses anonymous callers over trusted TLS")


def check_kafka(stack, report):
    with report.guarded("kafka cluster"):
        import runtime

        clusters = json.loads(runtime.kubectl(stack.identity, "get", "kafka", "-o", "json"))["items"]
        require(clusters, "No Strimzi Kafka resource exists")
        for item in clusters:
            ready = [c for c in item["status"]["conditions"] if c["type"] == "Ready"]
            require(ready and ready[0]["status"] == "True", "Kafka cluster is not Ready: " + item["metadata"]["name"])
        report.passed("kafka cluster", f"{len(clusters)} Strimzi cluster(s) report Ready")


SECTIONS = (("prometheus", check_prometheus), ("rules", check_rules), ("alertmanager", check_alertmanager),
            ("zipkin", check_zipkin), ("logs", check_logs), ("grafana", check_grafana), ("kibana", check_kibana),
            ("kafka", check_kafka), ("edge", check_edge))


def run_checks(stack, report, sections=SECTIONS):
    for name, check in sections:
        with report.guarded(name):
            check(stack, report)


def main():
    parser = argparse.ArgumentParser(description="Verify the running bank11 observability stack over its real HTTP APIs")
    parser.add_argument("--state-dir", type=Path, required=True)
    parser.add_argument("--secrets-dir", type=Path, default=Path(os.environ.get(
        "BANK_SECRETS_DIR", Path.home() / ".config/java-engineering-course/sprint11")))
    parser.add_argument("--report", type=Path, help="private JSON report; default: observability-report.json in the state directory")
    arguments = parser.parse_args()
    sys.path.insert(0, str(PROJECT / "deployment/kubernetes"))
    import bao
    import runtime

    # Receipt bootstrap проверяет точные идентификаторы kind и UID кластера; контекст по умолчанию не используется.
    identity = runtime.owned_identity(arguments.state_dir)
    secrets_dir = runtime.require_private_source(arguments.secrets_dir)
    prepared = json.loads((arguments.state_dir / "source.json").read_text(encoding="utf-8"))
    needles = secret_needles(secrets_dir, prepared["secret_files"])
    report = Report(needles)
    with Forwards(identity) as forwards:
        stack = Stack(PROJECT, identity, secrets_dir, prepared, forwards, bao.secret_text, needles)
        run_checks(stack, report)
    path = arguments.report or arguments.state_dir / "observability-report.json"
    runtime.save_receipt(path, report.document(identity["cluster"]))
    passed = sum(1 for check in report.checks if check["status"] == "PASS")
    skipped = sum(1 for check in report.checks if check["status"] == "SKIP")
    if report.failures():
        print(f"FAIL {len(report.failures())} of {len(report.checks)} observability checks failed; report: {path}", file=sys.stderr)
        sys.exit(1)
    print(f"PASS Kubernetes observability verification: {passed} checks passed, {skipped} skipped; report: {path}")


if __name__ == "__main__":
    try:
        main()
    except (VerificationError, OSError, subprocess.SubprocessError, ValueError, RuntimeError) as failure:
        print("FAIL " + (str(failure) if isinstance(failure, VerificationError) else type(failure).__name__), file=sys.stderr)
        sys.exit(1)
