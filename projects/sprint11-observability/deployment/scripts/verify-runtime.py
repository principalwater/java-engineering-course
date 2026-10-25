#!/usr/bin/env python3
"""Конечная проверка настоящего банка: TLS, OIDC, деньги, доставка и восстановление."""

import argparse
import calendar
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime, timezone
import hashlib
from html.parser import HTMLParser
import http.client
from http.cookiejar import CookieJar
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import socket
import ssl
import subprocess
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlencode, urljoin, urlsplit
from urllib.request import HTTPSHandler, HTTPRedirectHandler, HTTPCookieProcessor, ProxyHandler, Request, build_opener
import uuid


HOSTS = frozenset(("bank.valgrind-home.localhost", "auth-bank.valgrind-home.localhost", "insights.valgrind-home.localhost"))
CORE_APPS = ("front", "gateway", "accounts", "cash", "transfer", "notifications")
STORES = ("database", "discovery", "authorization", "edge")
HTTP_TIMEOUT = 10
WAIT_SECONDS = 120
MAX_RESPONSE_BYTES = 2_000_000
DIAGNOSTIC_TAIL_LINES = 40
DIAGNOSTIC_MAX_CHARS = 8192


class VerificationError(RuntimeError):
    """Сообщение не включает credentials, cookies, URI query или внешнее тело."""


class TransportUnavailable(VerificationError):
    pass


def require(condition, message):
    if not condition:
        raise VerificationError(message)


def diagnostic_output(label, output, private_values):
    if not output:
        return
    text = output.decode(errors="replace") if isinstance(output, bytes) else output
    text = text.replace("\r\n", "\n")
    redactions = set()
    for value in private_values:
        value = value.strip().replace("\r\n", "\n")
        if value:
            redactions.update((value, json.dumps(value)[1:-1], *value.splitlines()))
    for value in sorted(redactions, key=len, reverse=True):
        if value:
            text = text.replace(value, "[redacted]")
    # Сначала убираем секрет целиком и строки PEM, затем ограничиваем хвост.
    text = re.sub(r"[\x00-\x08\x0b-\x1f\x7f]", "", text)
    tail = "\n".join(text.splitlines()[-DIAGNOSTIC_TAIL_LINES:])[-DIAGNOSTIC_MAX_CHARS:]
    for line in tail.splitlines():
        print("DIAGNOSTIC " + label + ": " + line, file=sys.stderr)


def run(command, *, input_text=None, timeout=30, env=None, label=None, private_values=None):
    try:
        result = subprocess.run(command, input=input_text, text=True, capture_output=True, timeout=timeout, env=env)
    except subprocess.TimeoutExpired as failure:
        if label is None:
            raise
        if private_values is not None:
            diagnostic_output(label + " stdout", failure.stdout, private_values)
            diagnostic_output(label + " stderr", failure.stderr, private_values)
        raise VerificationError("Command timed out: " + label) from None
    except OSError as failure:
        if label is None:
            raise
        raise VerificationError("Could not start command: " + label + " (" + type(failure).__name__ + ")") from None
    if result.returncode != 0:
        if private_values is not None:
            diagnostic_output((label or "command") + " stdout", result.stdout, private_values)
            diagnostic_output((label or "command") + " stderr", result.stderr, private_values)
        description = label or (command[0] + " " + command[1])
        raise VerificationError("Command failed: " + description + " (exit " + str(result.returncode) + ")")
    return result.stdout


def wait_for(label, condition, *, timeout=WAIT_SECONDS):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            value = condition()
            if value:
                return value
        except (TransportUnavailable, URLError, TimeoutError, ConnectionError):
            pass
        time.sleep(1)
    raise VerificationError("Timed out: " + label)


def allowed_url(url):
    parsed = urlsplit(url)
    return parsed.scheme == "https" and parsed.hostname in HOSTS and parsed.username is None and parsed.password is None


class LocalHTTPSConnection(http.client.HTTPSConnection):
    def connect(self):
        require(self.host in HOSTS and self._tunnel_host is None, "Unexpected TLS peer")
        # Меняется только разрешение двух локальных DNS-имён; SNI и проверка сертификата сохраняются.
        plain = socket.create_connection(("127.0.0.1", self.port), self.timeout, self.source_address)
        self.sock = self._context.wrap_socket(plain, server_hostname=self.host)


class LocalHTTPSHandler(HTTPSHandler):
    def https_open(self, request):
        return self.do_open(LocalHTTPSConnection, request, context=self._context)


class SafeRedirect(HTTPRedirectHandler):
    def __init__(self, base_url, callback_path="/login/oauth2/code/bank"):
        self.base_url = base_url
        self.callback_path = callback_path
        self.paths = []
        self.authorization_redirects = 0

    def redirect_request(self, request, response, code, message, headers, target):
        require(allowed_url(target), "Unexpected authentication redirect")
        parsed = urlsplit(target)
        require(parsed.port == urlsplit(self.base_url).port, "Unexpected authentication redirect port")
        query = parse_qs(parsed.query)
        if parsed.path.endswith("/protocol/openid-connect/auth"):
            require(query.get("redirect_uri") == [self.base_url + self.callback_path], "OIDC callback origin was not canonical HTTPS")
            require(query.get("response_type") == ["code"] and query.get("code_challenge_method") == ["S256"]
                    and bool(query.get("state")) and bool(query.get("code_challenge")), "OIDC flow did not require PKCE and state")
            self.authorization_redirects += 1
        if parsed.path.endswith("/protocol/openid-connect/logout"):
            require(query.get("post_logout_redirect_uri") == [self.base_url + "/login?logout"], "Logout redirect origin was not canonical HTTPS")
        self.paths.append(parsed.path)
        return super().redirect_request(request, response, code, message, headers, target)


class LoginForm(HTMLParser):
    def __init__(self, form_id="kc-form-login"):
        super().__init__(convert_charrefs=True)
        self.action = None
        self.form_id = form_id
        self.fields = {}
        self.hidden = {}
        self.selected = False

    def handle_starttag(self, tag, attributes):
        values = dict(attributes)
        if tag == "form":
            self.selected = values.get("id") == self.form_id
            if self.selected:
                require(self.action is None, "Ambiguous login form")
                self.action = values.get("action")
        if tag == "input" and self.selected and values.get("name"):
            if values.get("type") == "hidden":
                self.hidden[values["name"]] = values.get("value", "")
            if values["name"] in ("email", "firstName", "lastName"):
                self.fields[values["name"]] = values.get("value", "")

    def handle_endtag(self, tag):
        if tag == "form":
            self.selected = False


class User:
    def __init__(self, base_url, secrets_dir, *, callback_path="/login/oauth2/code/bank"):
        require(allowed_url(base_url), "Base URL must use a canonical bank TLS hostname")
        self.base_url = base_url.rstrip("/")
        self.secrets_dir = Path(secrets_dir).expanduser()
        context = ssl.create_default_context(cafile=str(self.secrets_dir / "tls_ca.pem"))
        self.redirect = SafeRedirect(self.base_url, callback_path)
        self.cookies = CookieJar()
        self.http = build_opener(ProxyHandler({}), LocalHTTPSHandler(context=context),
                                 HTTPCookieProcessor(self.cookies), self.redirect)
        self.session = None

    def request(self, method, path, body=None, *, csrf=True, form=None, headers_extra=None):
        url = urljoin(self.base_url + "/", path)
        require(allowed_url(url) and urlsplit(url).port == urlsplit(self.base_url).port, "Unexpected HTTP target")
        headers = {"Accept": "application/json, text/html", **(headers_extra or {})}
        data = None
        if body is not None:
            data = json.dumps(body, separators=(",", ":")).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if form is not None:
            data = urlencode(form).encode("utf-8")
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        if csrf and method not in ("GET", "HEAD") and self.session:
            headers[self.session["csrfHeader"]] = self.session["csrfToken"]
        request = Request(url, data=data, headers=headers, method=method)
        try:
            response = self.http.open(request, timeout=HTTP_TIMEOUT)
        except HTTPError as error:
            response = error
        except (URLError, TimeoutError, OSError, http.client.HTTPException):
            raise TransportUnavailable("HTTP transport unavailable") from None
        try:
            with response:
                payload = response.read(MAX_RESPONSE_BYTES + 1)
                require(len(payload) <= MAX_RESPONSE_BYTES, "HTTP response exceeded verification limit")
                return response.status, response.geturl(), payload
        except (TimeoutError, OSError, http.client.HTTPException):
            raise TransportUnavailable("HTTP response transport unavailable") from None

    def json(self, method, path, body=None, *, csrf=True, expected=200):
        status, _, payload = self.request(method, path, body, csrf=csrf)
        expected_statuses = (expected,) if isinstance(expected, int) else expected
        require(status in expected_statuses, "Unexpected status for " + path.split("?")[0] + ": " + str(status))
        if not payload:
            return None
        try:
            return json.loads(payload)
        except (json.JSONDecodeError, UnicodeDecodeError):
            raise VerificationError("Expected JSON from " + path.split("?")[0]) from None

    def login(self, login):
        require(login in ("alice", "bob", "analyst"), "Only demo identities may be probed")
        status, url, page = self.request("GET", "/oauth2/authorization/bank")
        require(status == 200 and urlsplit(url).hostname == "auth-bank.valgrind-home.localhost", "Authorization redirect failed")
        form = LoginForm()
        form.feed(page.decode("utf-8"))
        require(form.action is not None, "Keycloak login form missing")
        action = urljoin(url, form.action)
        require(allowed_url(action) and urlsplit(action).hostname == "auth-bank.valgrind-home.localhost"
                and "/login-actions/authenticate" in urlsplit(action).path, "Unexpected login form action")
        secret = self.secrets_dir / ("demo_" + login + "_password")
        require(secret.is_file() and not secret.is_symlink(), "Demo password file is unavailable")
        fields = dict(form.hidden)
        fields.update(username=login, password=secret.read_text(encoding="utf-8").strip())
        status, final, page = self.request("POST", action, csrf=False, form=fields)
        if urlsplit(final).hostname == "auth-bank.valgrind-home.localhost" and "/login-actions/required-action" in urlsplit(final).path:
            profile = LoginForm("kc-update-profile-form")
            profile.feed(page.decode("utf-8"))
            require(profile.action is not None and {"email", "firstName", "lastName"}.issubset(profile.fields), "Unexpected Keycloak required action")
            action = urljoin(final, profile.action)
            require(allowed_url(action) and "/login-actions/required-action" in urlsplit(action).path, "Unexpected profile action")
            fields = {**profile.hidden, **profile.fields, "email": login + "@example.test"}
            status, final, _ = self.request("POST", action, csrf=False, form=fields)
        require(status == 200 and urlsplit(final).path == "/cabinet"
                and "/login/oauth2/code/bank" in self.redirect.paths, "Authorization Code callback failed")
        self.session = self.json("GET", "/ui/session")
        require(self.session.get("login") == login and all(self.session.get(key) for key in
                ("csrfToken", "csrfHeader", "csrfParameter")), "Session identity or CSRF contract failed")
        return self

    def account(self):
        return self.json("GET", "/ui/account")

    def money(self, path, command, *, timeout=WAIT_SECONDS):
        # После неизвестного исхода повторяется одна неизменная команда с исходным UUID.
        def completed():
            result = self.json("POST", path, command, expected=(200, 202, 503))
            if result and result.get("status") in ("SUCCEEDED", "REJECTED"):
                return result
            return None
        return wait_for("money operation outcome", completed, timeout=timeout)

    def logout(self):
        status, url, _ = self.request("POST", "/logout", form={self.session["csrfParameter"]: self.session["csrfToken"]})
        require(status == 200 and urlsplit(url).path == "/login", "Front/IdP logout did not finish")
        require(self.request("GET", "/ui/account")[0] == 401, "Private API remained accessible after logout")
        status, url, _ = self.request("GET", "/cabinet")
        require(status == 200 and urlsplit(url).path == "/login", "Private page remained accessible after logout")
        status, url, page = self.request("GET", "/oauth2/authorization/bank")
        form = LoginForm()
        form.feed(page.decode("utf-8"))
        require(status == 200 and urlsplit(url).hostname == "auth-bank.valgrind-home.localhost"
                and form.action is not None, "IdP session remained authenticated after logout")
        self.session = None


def kube(identity, *arguments, input_text=None, timeout=30):
    return run(["kubectl", "--kubeconfig", identity["kubeconfig"], "--context", identity["context"],
                "--namespace", identity["namespace"], *arguments], input_text=input_text, timeout=timeout,
               label="Kubernetes " + arguments[0])


def pg(containers, sql):
    # Пароль читается только внутри Pod; SQL не содержит credentials.
    command = 'PGPASSWORD="$(cat /run/secrets/db_admin_password)" exec psql -X -qAt -h 127.0.0.1 -U bank_owner -d bank -v ON_ERROR_STOP=1'
    return kube(containers, "exec", "-i", "statefulset/database", "--", "sh", "-c", command, input_text=sql + "\n", timeout=20).strip()


def scalar(containers, sql):
    value = pg(containers, sql)
    require(re.fullmatch(r"[0-9]+", value) is not None, "Expected a scalar database assertion")
    return int(value)


def checked_uuid(value):
    return str(uuid.UUID(str(value)))


def cash(amount, kind="DEPOSIT"):
    return {"operationId": str(uuid.uuid4()), "type": kind, "amountMinor": amount}


def transfer(recipient, amount):
    return {"operationId": str(uuid.uuid4()), "recipientId": checked_uuid(recipient), "amountMinor": amount}


def assert_success(result):
    require(result.get("status") == "SUCCEEDED", "Expected successful money operation")
    require(isinstance(result.get("balanceMinor"), int), "Successful operation has no own balance")


def change_state(containers, service, action):
    require(service in (*CORE_APPS, "cassandra", "clickhouse") and action in ("stop", "start"), "Unexpected failure-probe target")
    replicas = "0" if action == "stop" else "1"
    resource = ("statefulset/" if service in ("cassandra", "clickhouse") else "deployment/") + service
    kube(containers, "scale", resource, "--replicas=" + replicas)
    if action == "stop":
        kube(containers, "wait", "--for=delete", "pod", "-l", "app=" + service, "--timeout=90s", timeout=100)
    else:
        kube(containers, "rollout", "status", resource, "--timeout=180s", timeout=190)


def discovery(containers, analytics):
    names = (*(name for name in CORE_APPS if name != "notifications"), *(("analytics",) if analytics else ()))
    commands = ["ls /services", *("ls /services/" + name for name in names), "get /config/application/bank.config.revision", "quit"]
    output = kube(containers, "exec", "-i", "statefulset/discovery", "--", "zkCli.sh", "-server", "127.0.0.1:2181", "-waitforconnection", "-timeout", "5000",
                  input_text="\n".join(commands) + "\n", timeout=20)
    rows = [line.strip()[1:-1].split(", ") for line in output.splitlines() if re.fullmatch(r"\[[A-Za-z0-9_, .:-]*\]", line.strip())]
    if len(rows) < len(names) + 1 or not set(names).issubset(set(rows[0])):
        return False
    if not all(len(row) == 1 and row[0] for row in rows[1:len(names) + 1]):
        return False
    require("sprint11-v1" in output.splitlines(), "Distributed configuration revision is missing")
    for service in ("accounts", "cash", "transfer", *(("analytics",) if analytics else ())):
        logs = kube(containers, "logs", "deployment/" + service, "--tail=1000", timeout=15)
        if "Shared bank configuration revision: sprint11-v1" not in logs:
            return False
    print("PASS discovery: " + str(len(names)) + " single instances; Java shared configuration revision")
    return True


def verify_profiles(alice, bob):
    original = alice.account()
    bob_before = bob.account()
    status, _, _ = bob.request("POST", "/ui/cash", cash(1), csrf=False,
                               headers_extra={alice.session["csrfHeader"]: alice.session["csrfToken"]})
    require(status == 403 and bob.account()["balanceMinor"] == bob_before["balanceMinor"]
            and alice.account()["balanceMinor"] == original["balanceMinor"], "Cross-session CSRF changed an account")
    require(alice.request("PUT", "/ui/account", {"fullName": "x" * 40_000, "birthDate": "1990-06-15", "version": original["version"]})[0] == 413, "Edge request body limit was not enforced")
    require(alice.request("PUT", "/ui/account", {"fullName": "Invalid", "birthDate": "1990-06-15", "version": original["version"]}, csrf=False)[0] == 403, "Mutation without CSRF was not rejected")
    today = datetime.now(timezone.utc).date()
    birthday = date(today.year - 18, today.month, min(today.day, calendar.monthrange(today.year - 18, today.month)[1]))
    alice.json("PUT", "/ui/account", {"fullName": "Age boundary", "birthDate": birthday.isoformat(), "version": original["version"]}, expected=422)
    for user, full_name in ((alice, "Иванова Алиса Сергеевна"), (bob, "Петров Борис Иванович")):
        account = user.account()
        update = {"fullName": full_name, "birthDate": account.get("birthDate") or "1990-06-15",
                  "version": account["version"]}
        changed = user.json("PUT", "/ui/account", update)
        require(changed["version"] == account["version"] + 1, "Profile version did not advance")
        user.json("PUT", "/ui/account", update, expected=409)
    a, b = alice.account(), bob.account()
    require(a["accountId"] != b["accountId"], "User owners collapsed")
    owned = alice.json("GET", "/ui/account?ownerId=" + checked_uuid(b["accountId"]))
    require(owned["accountId"] == a["accountId"], "Foreign owner parameter changed current account")
    recipients = alice.json("GET", "/ui/recipients?search=bob")["items"]
    require(any(item["accountId"] == b["accountId"] for item in recipients), "Recipient is missing")
    require(all(set(item) == {"accountId", "login", "fullName"} for item in recipients), "Recipient projection leaked account fields")
    print("PASS OIDC sessions, CSRF, profile validation/version and owner isolation")
    return a, b


def _verify_withdrawal_rejection(user):
    before = user.account()["balanceMinor"]
    command = cash(before + 1000, "WITHDRAW")
    require(command["amountMinor"] <= 1_000_000_000_000, "Demo balance exceeds the bounded fixture budget")
    rejected = user.money("/ui/cash", command)
    require(rejected.get("status") == "REJECTED" and rejected.get("error") == "INSUFFICIENT_FUNDS", "Missing terminal rejection")
    require(user.account()["balanceMinor"] == before, "Rejected withdrawal changed balance")
    return command, rejected


def verify_money(alice, bob, containers):
    a, b = alice.account(), bob.account()
    initial = a["balanceMinor"]
    funded = alice.money("/ui/cash", cash(10_000))
    assert_success(funded)
    require(funded["balanceMinor"] == initial + 10_000, "Deposit balance mismatch")
    debit = cash(1000, "WITHDRAW")
    original = alice.money("/ui/cash", debit)
    assert_success(original)
    assert_success(alice.money("/ui/cash", cash(123)))
    require(alice.money("/ui/cash", debit) == original, "Replay did not return the original outcome")
    before = alice.account()["balanceMinor"]
    alice.json("POST", "/ui/cash", {**debit, "amountMinor": 1001}, expected=409)
    alice.json("POST", "/ui/cash", {**cash(1), "amountMinor": 1.5}, expected=400)
    require(alice.account()["balanceMinor"] == before, "Rejected payload changed balance")
    amount = transfer(b["accountId"], 500)
    before_b = bob.account()["balanceMinor"]
    sent = alice.money("/ui/transfers", amount)
    assert_success(sent)
    require(alice.money("/ui/transfers", amount) == sent, "Transfer replay changed outcome")
    require(alice.account()["balanceMinor"] == before - 500 and bob.account()["balanceMinor"] == before_b + 500,
            "Transfer did not conserve the two own balances")
    rejected_command, rejected = _verify_withdrawal_rejection(alice)
    assert_success(alice.money("/ui/cash", cash(2000)))
    require(alice.money("/ui/cash", rejected_command) == rejected, "Rejected command changed after top-up")
    require(scalar(containers, "SELECT count(*) FROM accounts.ledger_entry WHERE owner_id='" + checked_uuid(a["accountId"])
                   + "' AND request_id='" + checked_uuid(debit["operationId"]) + "'") == 1, "Withdraw ledger effect duplicated")
    history = bob.json("GET", "/ui/history")["items"]
    require(all(item["operationId"] != debit["operationId"] for item in history), "Foreign history leaked")
    print("PASS cash/transfer immutable replay, terminal rejection, conservation and own history")


def notification_id(containers, owner, kind, operation_id):
    identity = json.loads(pg(containers, "SELECT json_build_array(issuer,subject)::text FROM accounts.account WHERE id='"
                             + checked_uuid(owner) + "'"))
    key = str(len(identity[0])) + ":" + identity[0] + str(len(identity[1])) + ":" + identity[1] + ":" + kind + ":" + checked_uuid(operation_id)
    return str(uuid.UUID(bytes=hashlib.md5(key.encode("utf-8")).digest(), version=3))


def delivered_notification(user, event_id, owner):
    page = user.json("GET", "/ui/notifications?limit=20", expected=(200, 503))
    if "notifications" not in page:
        return None
    require(page.get("accountId") == owner, "Notification response owner mismatch")
    return next((entry for entry in page["notifications"]["items"] if entry["eventId"] == event_id), None)


def verify_notifications(alice, bob, containers):
    owner = alice.account()["accountId"]
    command = cash(17)
    result = alice.money("/ui/cash", command)
    assert_success(result)
    event_id = notification_id(containers, owner, "DEPOSIT", command["operationId"])
    entry = wait_for("cabinet notification delivery", lambda: delivered_notification(alice, event_id, owner))
    require(entry["type"] == "DEPOSIT_SUCCEEDED" and entry["amountMinor"] == 17
            and entry["balanceMinor"] == result["balanceMinor"], "Cabinet notification is not the delivered operation")
    require(set(entry) == {"eventId", "type", "amountMinor", "balanceMinor", "occurredAt", "source", "readAt"}, "Notification projection leaked internal identity")
    require(entry["source"] == "CASH" and entry["readAt"] is None, "New notification read state is invalid")
    mark = {"source": entry["source"], "eventId": event_id}
    bob.json("POST", "/ui/notifications/read", mark, expected=404)
    read = alice.json("POST", "/ui/notifications/read", mark)
    repeated = alice.json("POST", "/ui/notifications/read", mark)
    require(read["accountId"] == owner and read["notification"]["readAt"] is not None
            and repeated == read, "Notification read did not preserve first-read identity")
    foreign = bob.json("GET", "/ui/notifications?ownerId=" + checked_uuid(owner))["notifications"]["items"]
    require(all(item["eventId"] != event_id for item in foreign), "Foreign notification leaked through owner parameter")
    first = alice.json("GET", "/ui/notifications?limit=1")["notifications"]
    require(first["nextCursor"] is not None, "Notification fixture has no next page")
    older = alice.json("GET", "/ui/notifications?limit=1&before=" + first["nextCursor"])["notifications"]
    require(len(older["items"]) == 1 and older["items"][0]["eventId"] != first["items"][0]["eventId"], "Notification keyset repeated a receipt")
    require(alice.money("/ui/cash", command) == result, "Notification lookup changed immutable payment outcome")
    require(scalar(containers, "SELECT count(*) FROM notifications.bank_notification WHERE caller='cash-service' AND event_id='"
                   + event_id + "'") == 1, "Cabinet notification replay duplicated receipt")
    alice.json("GET", "/ui/notifications?limit=51", expected=400)
    alice.json("GET", "/ui/notifications?before=0", expected=400)
    print("PASS actual outbox-to-cabinet notification delivery, owner isolation, stable pages and dedup")


def verify_load(alice, containers):
    before = alice.account()
    commands = [cash(1) for _ in range(24)]
    deadline = time.monotonic() + WAIT_SECONDS
    def apply(command):
        started = time.monotonic()
        require(started < deadline, "Finite load deadline exceeded")
        assert_success(alice.money("/ui/cash", command, timeout=deadline - started))
        return time.monotonic() - started
    with ThreadPoolExecutor(max_workers=4) as workers:
        latencies = list(workers.map(apply, commands, timeout=WAIT_SECONDS + HTTP_TIMEOUT))
    for command in commands:
        assert_success(alice.money("/ui/cash", command))
    require(alice.account()["balanceMinor"] == before["balanceMinor"] + 24, "Concurrent cash/replay sum mismatch")
    identifiers = ",".join("'" + checked_uuid(command["operationId"]) + "'" for command in commands)
    count = scalar(containers, "SELECT count(*) FROM accounts.ledger_entry WHERE owner_id='" + checked_uuid(before["accountId"])
                   + "' AND request_id IN (" + identifiers + ")")
    require(count == 24, "Concurrent ledger effect count mismatch")
    p95 = sorted(latencies)[math.ceil(len(latencies) * 0.95) - 1]
    print("PASS finite load: 24 commands, 4 clients, 24 replays; observed p95=" + format(p95, ".3f") + "s")


def verify_recovery(alice, bob, containers):
    before = alice.account()
    command = cash(77)
    try:
        change_state(containers, "accounts", "stop")
        status, _, payload = alice.request("POST", "/ui/cash", command)
        require(status in (202, 503), "Unavailable Accounts did not expose an unknown outcome")
        if status == 202:
            result = json.loads(payload)
            require(result.get("status") == "PENDING" and result.get("balanceMinor") is None, "Pending result invented a balance")
    finally:
        change_state(containers, "accounts", "start")
    assert_success(alice.money("/ui/cash", command))
    require(alice.account()["balanceMinor"] == before["balanceMinor"] + 77, "Recovery duplicated deposit")
    require(scalar(containers, "SELECT count(*) FROM accounts.ledger_entry WHERE owner_id='" + checked_uuid(before["accountId"])
                   + "' AND request_id='" + checked_uuid(command["operationId"]) + "'") == 1, "Recovered ledger effect count mismatch")
    a, b = alice.account(), bob.account()
    command = transfer(b["accountId"], 31)
    event_id = notification_id(containers, a["accountId"], "TRANSFER", command["operationId"])
    try:
        change_state(containers, "notifications", "stop")
        alice.json("GET", "/ui/notifications", expected=200)
        original = alice.money("/ui/transfers", command)
        assert_success(original)
        require(alice.account()["balanceMinor"] == a["balanceMinor"] - 31
                and bob.account()["balanceMinor"] == b["balanceMinor"] + 31, "Notification failure blocked the ledger")
        wait_for("broker publication while Notifications is stopped", lambda: scalar(containers,
                 "SELECT count(*) FROM transfer.bank_kafka_outbox WHERE message_id='" + event_id + "' AND status='PUBLISHED'") == 1)
        require(scalar(containers, "SELECT count(*) FROM notifications.bank_notification WHERE caller='transfer-service' AND event_id='"
                       + event_id + "'") == 0, "Stopped consumer unexpectedly accepted the receipt")
    finally:
        change_state(containers, "notifications", "start")
    wait_for("notification delivery", lambda: scalar(containers, "SELECT count(*) FROM notifications.bank_notification WHERE caller='transfer-service' AND event_id='"
             + event_id + "'") == 1)
    wait_for("notification outbox completion", lambda: scalar(containers, "SELECT count(*) FROM transfer.bank_kafka_outbox WHERE message_id='"
             + event_id + "' AND status='PUBLISHED'") == 1)
    entry = wait_for("recovered cabinet notification", lambda: delivered_notification(alice, event_id, a["accountId"]))
    require(entry["type"] == "TRANSFER_SUCCEEDED" and entry["amountMinor"] == 31
            and entry["balanceMinor"] == original["balanceMinor"], "Recovered receipt was not delivered to cabinet")
    require(all(item["eventId"] != event_id for item in bob.json("GET", "/ui/notifications")["notifications"]["items"]), "Recovered notification leaked to recipient")
    require(alice.money("/ui/transfers", command) == original, "Notification recovery changed transfer outcome")
    require(alice.account()["balanceMinor"] == a["balanceMinor"] - 31
            and bob.account()["balanceMinor"] == b["balanceMinor"] + 31, "Notification recovery repeated money effects")
    print("PASS Accounts unknown-outcome recovery and Notifications outage/dedup without repeated money effects")


def verify_analytics(alice, analyst, containers):
    require(alice.request("GET", "/ui/analytics/business")[0] == 403, "Customer business analytics access was not denied")
    before = wait_for("personal analytics projection", lambda: (value if (value := alice.json("GET", "/ui/analytics")).get("available") else None))
    event = {"eventId": str(uuid.uuid4())}
    alice.json("POST", "/ui/analytics/views", event, expected=204)
    alice.json("POST", "/ui/analytics/views", event, expected=204)
    after = wait_for("deduplicated view projection", lambda: (value if (value := alice.json("GET", "/ui/analytics")).get("available")
                    and value.get("viewCount") == before["viewCount"] + 1 else None))
    business = wait_for("analyst business projection", lambda: (value if (value := analyst.json("GET", "/ui/analytics/business")).get("available") else None))
    require(after.get("operationsCount", 0) > 0 and business.get("accountsCount", 0) >= 2, "Analytics projection has no actual bank facts")
    owner = checked_uuid(alice.account()["accountId"])
    query = "SELECT count(*) FROM analytics.analytics_inbox WHERE owner_id='" + owner + "' AND event_type='SCREEN_OPENED'"
    count = scalar(containers, query)
    activity = {"eventId": str(uuid.uuid4()), "type": "SCREEN_OPENED", "section": "HISTORY"}
    alice.json("POST", "/ui/analytics/activities", activity, expected=204)
    alice.json("POST", "/ui/analytics/activities", activity, expected=204)
    alice.json("POST", "/ui/analytics/activities", {**activity, "eventId": str(uuid.uuid4()), "amountMinor": 10}, expected=400)
    wait_for("Cassandra-derived UI batch acknowledgement", lambda: scalar(containers, query + " AND cassandra_done AND warehouse_done") == count + 1)
    require(scalar(containers, query) == count + 1, "UI replay or rejected monetary field changed admission count")
    require(after.get("accountId") == owner and after.get("month") == datetime.now(timezone.utc).strftime("%Y-%m"),
            "Personal analytics identity or UTC month is missing")
    print("PASS optional personal/analyst analytics and duplicate view; customer business access denied")


def verify_bi_database(identity):
    # Проверяем саму БД: ограничения UI не заменяют права сервисной учётной записи.
    script = '''set -eu
umask 077
directory=$(mktemp -d /tmp/bank-bi-probe.XXXXXX)
trap 'rm -rf "$directory"' EXIT
password=$(cat /run/secrets/analytics_bi_password)
printf '<config><user>bank_bi</user><password>%s</password><host>127.0.0.1</host></config>\\n' "$password" > "$directory/client.xml"
unset password
clickhouse-client --config-file="$directory/client.xml" --query 'SELECT count() FROM bank_analytics.financial_daily' > /dev/null
while IFS= read -r query; do
    if clickhouse-client --config-file="$directory/client.xml" --query "$query" > /dev/null 2> "$directory/error"; then
        exit 1
    fi
    grep -Eq 'ACCESS_DENIED|READONLY|SETTING_CONSTRAINT_VIOLATION' "$directory/error"
done <<'QUERIES'
SELECT count() FROM bank_analytics.events
INSERT INTO bank_analytics.events (event_id, owner_id, event_type, amount_minor, occurred_at, version) SELECT event_id, owner_id, event_type, amount_minor, occurred_at, version FROM bank_analytics.events WHERE 0
SELECT 1 SETTINGS max_memory_usage=0
SELECT 1 SETTINGS max_rows_to_read=0
QUERIES
'''
    kube(identity, "exec", "-i", "statefulset/clickhouse", "--", "sh", "-s", input_text=script)
    print("PASS BI database denies raw rows, writes and unlimited query settings")


def verify_bi(base_url, secrets_dir):
    bi_url = base_url.replace("bank.valgrind-home.localhost", "insights.valgrind-home.localhost")
    financial = {"Движение денег по дням", "Завершённые операции"}
    dashboards = {
        "bank-activity": financial | {"Действия в кабинете", "Активность по дням", "Доставка и прочтение", "Уведомления по дням"},
        "bank-financial": financial,
    }
    for username, permitted in (("alice", False), ("analyst", True)):
        user = User(bi_url, secrets_dir, callback_path="/oauth-authorized/bank")
        status, final, page = user.request("GET", "/login/bank")
        form = LoginForm()
        form.feed(page.decode("utf-8"))
        require(status == 200 and form.action is not None, "Superset did not use the bank OIDC login")
        action = urljoin(final, form.action)
        require(urlsplit(action).hostname == "auth-bank.valgrind-home.localhost"
                and "/login-actions/authenticate" in urlsplit(action).path, "Unexpected BI login action")
        password = (Path(secrets_dir) / ("demo_" + username + "_password")).read_text().strip()
        user.request("POST", action, form={**form.hidden, "username": username, "password": password}, csrf=False)
        charts = {}
        for slug, expected in dashboards.items():
            dashboard = "/api/v1/dashboard/" + slug
            status, _, payload = user.request("GET", dashboard)
            require(status == (200 if permitted else 401), "Superset analyst role boundary failed")
            if permitted:
                details = json.loads(payload)["result"]
                require(details["published"] is True, "Superset bank dashboard is not published: " + slug)
                related = user.json("GET", dashboard + "/charts")["result"]
                require(len(related) == len(expected) and {chart["slice_name"] for chart in related} == expected,
                        "Superset dashboard chart associations mismatch: " + slug)
                for chart in related:
                    previous = charts.setdefault(chart["slice_name"], chart)
                    require(previous["id"] == chart["id"], "Superset shared chart identity mismatch")
        if permitted:
            token = user.json("GET", "/api/v1/security/csrf_token/")["result"]
            user.session = {"csrfHeader": "X-CSRFToken", "csrfToken": token}
            denied = user.request("POST", "/api/v1/sqllab/execute/",
                                  {"database_id": 1, "sql": "SELECT 1", "runAsync": False},
                                  headers_extra={"Referer": bi_url + "/"})[0]
            require(denied == 403, "Superset analyst unexpectedly received SQL Lab access")
            require(len({chart["id"] for chart in charts.values()}) == 6, "Superset unique chart count mismatch")
            for chart in charts.values():
                data = user.json("GET", "/api/v1/chart/" + str(chart["id"]) + "/data/")
                require(data.get("result") and all(not result.get("error") for result in data["result"]),
                        "Superset analyst chart query failed")
                rows = [row for result in data["result"] for row in result.get("data", [])]
                if chart.get("slice_name") == "Действия в кабинете":
                    require(any(row.get("source") == "UI" and row.get("event_type") == "SCREEN_OPENED"
                                and row.get("events", 0) > 0 for row in rows), "BI is missing actual UI activity")
                if chart.get("slice_name") == "Доставка и прочтение":
                    require(any(row.get("read", 0) > 0 for row in rows), "BI is missing actual notification reads")
                if chart.get("slice_name") == "Завершённые операции":
                    require(any(row.get("operations", 0) > 0 and row.get("accounts", 0) > 0 for row in rows),
                            "BI is missing actual transactions and daily active accounts")
        user.request("GET", "/logout/")
    print("PASS Superset real OIDC analyst/customer isolation, both published dashboards, six unique charts and SQL Lab denial")


def verify(base_url, secrets_dir, containers, analytics=False, superset=False):
    """Проверяет готовый стенд без управления его жизненным циклом; возвращает сессии для fault probes."""
    startup_deadline = time.monotonic() + WAIT_SECONDS
    def ready(label, condition):
        require(time.monotonic() < startup_deadline, "Startup readiness deadline exceeded")
        return wait_for(label, condition, timeout=startup_deadline - time.monotonic())
    anonymous = User(base_url, secrets_dir)
    ready("bank TLS/login readiness", lambda: anonymous.request("GET", "/login")[0] == 200)
    for path in ("/actuator/health/liveness", "/actuator/health/readiness", "/actuator/env"):
        require(anonymous.request("GET", path)[0] == 404, "Edge exposed a management endpoint")
    with anonymous.http.open(Request(base_url + "/login", method="HEAD"), timeout=HTTP_TIMEOUT) as response:
        require(response.status == 200 and "h3=" not in response.headers.get("Alt-Svc", ""),
                "TCP-only ingress advertised unsupported HTTP/3")
    require(anonymous.request("GET", "/ui/account")[0] == 401, "Anonymous private API did not return 401")
    require(anonymous.request("GET", "/ui/notifications")[0] == 401, "Anonymous notification API did not return 401")
    authority = base_url.replace("bank.valgrind-home.localhost", "auth-bank.valgrind-home.localhost")
    ready("OIDC provider readiness", lambda: anonymous.request("GET", authority + "/realms/bank/.well-known/openid-configuration")[0] == 200)
    forwarded = {"X-Forwarded-Proto": "http", "X-Forwarded-Host": "evil.example", "X-Forwarded-Port": "80", "X-Forwarded-Prefix": "/evil"}
    for headers in ({"Forwarded": "proto=http;host=evil.example"}, forwarded, {"Forwarded": "proto=http;host=evil.example", **forwarded}):
        probe = User(base_url, secrets_dir)
        status, final, _ = probe.request("GET", "/oauth2/authorization/bank", headers_extra=headers)
        require(status == 200 and probe.redirect.authorization_redirects == 1 and urlsplit(final).hostname == "auth-bank.valgrind-home.localhost", "Forwarded-header isolation failed")
    print("PASS TLS and canonical HTTPS callbacks despite malicious forwarded headers")
    ready("single-instance ZooKeeper registration and Java configuration readiness", lambda: discovery(containers, analytics))
    require(scalar(containers, "SELECT count(*) FROM pg_roles WHERE rolname IN ('accounts','cash','transfer','notifications','analytics','keycloak') AND (rolsuper OR rolcreatedb OR rolcreaterole)") == 0,
            "Application database role has elevated permissions")
    require(scalar(containers, "SELECT count(*) FROM (VALUES ('accounts'),('cash'),('transfer'),('notifications'),('analytics'),('keycloak')) r(name) CROSS JOIN (VALUES ('accounts'),('cash'),('transfer'),('notifications'),('analytics'),('keycloak')) s(name) WHERE r.name<>s.name AND has_schema_privilege(r.name,s.name,'USAGE')") == 0,
            "Application role can use another schema")
    alice = User(base_url, secrets_dir).login("alice")
    bob = User(base_url, secrets_dir).login("bob")
    ready("Accounts readiness", lambda: alice.json("GET", "/ui/account", expected=(200, 503)).get("accountId"))
    verify_profiles(alice, bob)
    verify_money(alice, bob, containers)
    verify_notifications(alice, bob, containers)
    verify_load(alice, containers)
    analyst = User(base_url, secrets_dir).login("analyst") if analytics else None
    if analytics:
        require(alice.session.get("analyticsEnabled") is True, "Front analytics contour is not enabled")
        verify_analytics(alice, analyst, containers)
        if superset:
            verify_bi_database(containers)
            verify_bi(base_url, secrets_dir)
    return {"alice": alice, "bob": bob, "analyst": analyst}


def main():
    parser = argparse.ArgumentParser(description="Verify the running Kubernetes bank over real HTTPS/OIDC")
    parser.add_argument("--state-dir", type=Path, required=True)
    parser.add_argument("--secrets-dir", type=Path, default=Path(os.environ.get(
        "BANK_SECRETS_DIR", Path.home() / ".config/java-engineering-course/sprint11")))
    parser.add_argument("--analytics", action="store_true")
    parser.add_argument("--faults", action="store_true")
    arguments = parser.parse_args()
    # Bootstrap receipt проверяет exact kind IDs и Kubernetes UID; default context не используется.
    sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "deployment/kubernetes"))
    specification = importlib.util.spec_from_file_location("bank_kubernetes", Path(__file__).resolve().parents[2] / "deployment/kubernetes/runtime.py")
    cluster = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(cluster)
    identity = cluster.owned_identity(arguments.state_dir)
    prepared = json.loads((arguments.state_dir / "source.json").read_text(encoding="utf-8"))
    require(not arguments.analytics or prepared["analytics"], "Analytics verification requires its deployed contour")
    base = "https://bank.valgrind-home.localhost:" + str(identity["external_port"])
    users = verify(base, arguments.secrets_dir, identity, arguments.analytics,
                   arguments.analytics and prepared["superset"])
    try:
        if arguments.faults:
            verify_recovery(users["alice"], users["bob"], identity)
            if arguments.analytics:
                path = Path(__file__).resolve().parents[2] / "services/analytics/src/test/python/projection_recovery.py"
                specification = importlib.util.spec_from_file_location("bank_projection_recovery", path)
                recovery = importlib.util.module_from_spec(specification)
                specification.loader.exec_module(recovery)
                recovery.verify_analytics_failures(sys.modules[__name__], users, identity)
            # Перезапуск Accounts сбрасывает process-local counter; новый реальный отказ проверяет текущую JVM.
            _verify_withdrawal_rejection(users["alice"])
            print("PASS committed withdrawal rejection after recovery; balance unchanged")
    finally:
        for user in users.values():
            if user is not None:
                user.logout()
    print("PASS Kubernetes application verification; authenticated sessions logged out")


if __name__ == "__main__":
    try:
        main()
    except (VerificationError, OSError, subprocess.SubprocessError, ValueError, RuntimeError) as failure:
        message = str(failure) if isinstance(failure, VerificationError) else type(failure).__name__
        print("FAIL " + message, file=sys.stderr)
        sys.exit(1)
