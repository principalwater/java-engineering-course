#!/usr/bin/env python3
"""Настоящие HTTPS, SMTP и одноразовая ссылка; меняется пароль только созданного проверкой аккаунта."""

import argparse
import json
from http.cookies import SimpleCookie
import re
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlencode, urlparse
from urllib.request import Request, urlopen
import uuid
from runtime import wait_for
from smoke import CSRF_FIELD, MarketProbe, Page


def mailbox_messages(base, email):
    with urlopen(base + "/api/v1/search?" + urlencode({"query": "to:" + email}), timeout=5) as response:
        return json.load(response)["messages"]


def verify(probe, mailbox, proof):
    with probe.http.open(Request(probe.base + "/actuator/health"), timeout=5) as response:
        assert json.load(response)["status"] == "UP", "Health must use the session PING without broader Redis privileges"
    username, previous_password = probe.account_credentials
    email = username + "@example.test"
    another_browser = MarketProbe(probe.base, secrets_dir=probe.secrets_dir)
    another_browser.login(username, previous_password)
    unrelated = MarketProbe(probe.base, secrets_dir=probe.secrets_dir)
    unrelated.register_account("Независимый покупатель")
    guest = MarketProbe(probe.base, secrets_dir=probe.secrets_dir)
    request_path = "/account/recovery"
    unknown = guest.post(request_path, guest.read(request_path), email="missing-" + uuid.uuid4().hex + "@example.test")
    # Новый браузер заставляет сервер выставить cookie даже при поддельных proxy headers.
    guest = MarketProbe(probe.base, secrets_dir=probe.secrets_dir)
    forwarded = {"Forwarded": "host=foreign.example.test;proto=http",
                 "X-Forwarded-Host": "foreign.example.test", "X-Forwarded-Proto": "http"}
    with guest.http.open(Request(probe.base + request_path, headers=forwarded), timeout=15) as response:
        form = Page(response.url, response.read().decode())
        issued = SimpleCookie()
        for header in response.headers.get_all("Set-Cookie", []):
            issued.load(header)
        assert issued and all(cookie["secure"] and cookie["httponly"] for cookie in issued.values()), \
            "HTTPS sessions must use Secure and HttpOnly cookies"
    assert list(guest.cookies) and all(cookie.secure for cookie in guest.cookies), "Proxy headers overrode the HTTPS origin"
    # Публичная ссылка задаётся конфигурацией; клиент не выбирает её через proxy headers.
    spoofed = Request(probe.base + request_path, data=urlencode({CSRF_FIELD: form.token, "email": email}).encode(),
                      headers={"Content-Type": "application/x-www-form-urlencoded", **forwarded})
    with guest.http.open(spoofed, timeout=15) as response:
        known = Page(response.url, response.read().decode())
    assert unknown.url == known.url, "Recovery must not disclose whether an address exists"
    wait_for(lambda: len(mailbox_messages(mailbox, email)) == 1, "Recovery email")
    guest.post(request_path, guest.read(request_path), email=email)
    assert len(mailbox_messages(mailbox, email)) == 1, "Cooldown must not send a second email"
    message_id = mailbox_messages(mailbox, email)[0]["ID"]
    with urlopen(mailbox + "/api/v1/message/" + message_id, timeout=5) as response:
        text = json.load(response)["Text"]
    link = re.search(r"https://[^\s]+", text).group()
    assert link.startswith(probe.base + "/account/recovery/reset?"), "The recovery origin must be fixed"
    token = parse_qs(urlparse(link).query)["token"][0]
    with guest.http.open(link, timeout=10) as response:
        assert "no-store" in response.headers["Cache-Control"]
        assert response.headers["Referrer-Policy"] == "no-referrer"
        reset = Page(response.url, response.read().decode())
    password = "Reset-" + uuid.uuid4().hex
    result = guest.post("/account/recovery/reset", reset, token=token, password=password)
    assert urlparse(result.url).path == "/account/login", "Password reset must not log the user in"
    for browser in (probe, another_browser):
        assert urlparse(browser.read("/cabinet").url).path == "/account/login", "Old sessions must lose access"
    assert urlparse(unrelated.read("/cabinet").url).path == "/cabinet", "Another account must keep its session"
    login = guest.read("/account/login")
    rejected = guest.read("/account/login", urlencode({CSRF_FIELD: login.token, "username": username,
                          "password": previous_password}).encode(), "application/x-www-form-urlencoded")
    assert "error" in parse_qs(urlparse(rejected.url).query, keep_blank_values=True), "The previous password still works"
    probe.login(username, password)
    probe.account_credentials = (username, password)
    assert probe.read(proof["order_path"]).order_total == 400, "Password reset changed the owned order"
    try:
        guest.read("/account/recovery/reset", urlencode({CSRF_FIELD: reset.token, "token": token,
                   "password": "Another-" + uuid.uuid4().hex}).encode(), "application/x-www-form-urlencoded")
    except HTTPError as error:
        assert error.code == 400, "A consumed reset link must be rejected"
        error.read()
    else:
        raise AssertionError("The reset link was usable twice")
    print("HTTPS/SMTP recovery: fixed origin, safe cookies, one-time link, session revocation and retained order passed")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="https://shop.valgrind-home.localhost:8448")
    parser.add_argument("--payments-url", default="http://127.0.0.1:8098")
    parser.add_argument("--mailbox-url", default="http://127.0.0.1:18028")
    parser.add_argument("--secrets-dir")
    arguments = parser.parse_args()
    buyer = MarketProbe(arguments.base_url, arguments.payments_url, arguments.secrets_dir)
    verify(buyer, arguments.mailbox_url, buyer.run())
