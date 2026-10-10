#!/usr/bin/env python3
"""Проверяет настоящие form login, M2M JWT, роли, audience и logout двух сервисов."""
import base64
from functools import partial
import json
from pathlib import Path
import sys
import time
from urllib.error import URLError
from urllib.parse import urlencode, urljoin
from urllib.request import build_opener, HTTPCookieProcessor

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from http_fixture import Forms, NoRedirect, browser as create_browser, request as send_request

FRONT = "http://127.0.0.1:18181"
BACK = "http://127.0.0.1:18182"
PROVIDER = "http://127.0.0.1:18281"
ORIGINS = {FRONT, BACK, PROVIDER}
browser = partial(create_browser, ORIGINS)
request = partial(send_request, allowed_origins=ORIGINS)


def wait_ready():
    follow, direct = browser()
    deadline = time.monotonic() + 90
    while True:
        try:
            assert request(direct, FRONT + "/")[0] == 200
            assert request(direct, BACK + "/api/message")[0] == 401
            return follow, direct
        except URLError:
            if time.monotonic() >= deadline:
                raise RuntimeError("Service pair did not become ready") from None
            time.sleep(0.2)


def login_form(opener):
    code, _, body, _ = request(opener, FRONT + "/login")
    assert code == 200 and "Войти" in body
    assert "Неверное имя пользователя или пароль." not in body, "Plain login page must not display an authentication error"
    form = next(form for form in Forms(body).forms if form["action"] and form["action"].endswith("/login"))
    assert form["fields"].get("_csrf"), "Login form must contain a CSRF token"
    return form


def token(opener, client_id, secret):
    credentials = base64.b64encode((client_id + ":" + secret).encode()).decode()
    code, _, body, _ = request(opener, PROVIDER + "/realms/webflux-demo/protocol/openid-connect/token",
                              {"grant_type": "client_credentials"},
                              {"Authorization": "Basic " + credentials})
    assert code == 200, "Keycloak must issue the requested fixture token"
    return json.loads(body)["access_token"]


def main():
    values = dict(line.split("=", 1) for line in
                  (Path(__file__).resolve().parent / "target/lab.env").read_text().splitlines())
    follow, direct = wait_ready()
    code, headers, body, _ = request(direct, FRONT + "/message")
    assert code == 302 and headers["Location"].endswith("/login")
    form = login_form(follow)
    cookies = next(handler.cookiejar for handler in direct.handlers if isinstance(handler, HTTPCookieProcessor))
    before_login = next(cookie.value for cookie in cookies if cookie.name == "SESSION")
    assert request(direct, FRONT + "/login", {"username": "user", "password": values["COURSE_USER_PASSWORD"]})[0] == 403
    form["fields"].update(username="user", password="incorrect-password")
    code, headers, _, _ = request(direct, urljoin(FRONT, form["action"]), form["fields"])
    assert code == 302 and "error" in headers["Location"]
    assert request(direct, FRONT + "/message")[0] == 302
    print("Public pages, anonymous protection, CSRF and wrong password: passed")

    form = login_form(follow)
    form["fields"].update(username="user", password=values["COURSE_USER_PASSWORD"])
    code, _, body, _ = request(follow, urljoin(FRONT, form["action"]), form["fields"])
    assert code == 200 and "Hello World!" in body and "frontend-service" in body
    current_session = next(cookie.value for cookie in cookies if cookie.name == "SESSION")
    assert current_session != before_login, "Login must rotate the session ID"
    assert request(build_opener(NoRedirect()), FRONT + "/message", headers={"Cookie": "SESSION=" + before_login})[0] == 302
    print("Local login, session rotation and real frontend-to-backend Client Credentials call: passed")

    service_token = token(direct, "frontend-service", values["COURSE_CLIENT_SECRET"])
    header = {"Authorization": "Bearer " + service_token}
    code, response_headers, result, _ = request(direct, BACK + "/api/message", headers=header)
    assert code == 200 and result == "Hello World!"
    assert response_headers.get("Set-Cookie") is None, "Stateless backend must not create or expire session cookies"
    assert request(direct, BACK + "/api/message")[0] == 401
    no_role = token(direct, "viewer-test", values["COURSE_VIEWER_SECRET"])
    assert request(direct, BACK + "/api/message", headers={"Authorization": "Bearer " + no_role})[0] == 403
    wrong_audience = token(direct, "wrong-audience-test", values["COURSE_WRONG_AUDIENCE_SECRET"])
    assert request(direct, BACK + "/api/message", headers={"Authorization": "Bearer " + wrong_audience})[0] == 401
    encoded_header, encoded_payload, signature = service_token.split(".")
    payload = json.loads(base64.urlsafe_b64decode(encoded_payload + "=" * (-len(encoded_payload) % 4)))
    payload["probe"] = "tampered"
    tampered_payload = base64.urlsafe_b64encode(json.dumps(payload).encode()).decode().rstrip("=")
    tampered = ".".join((encoded_header, tampered_payload, signature))
    assert request(direct, BACK + "/api/message", headers={"Authorization": "Bearer " + tampered})[0] == 401
    print("Real JWT: allowed SERVICE, absent token, missing role, wrong audience and altered signature: passed")

    assert request(direct, FRONT + "/logout", {})[0] == 403
    code, _, body, _ = request(follow, FRONT + "/message")
    form = next(form for form in Forms(body).forms if form["action"] and form["action"].endswith("/logout"))
    assert code == 200 and form["fields"].get("_csrf")
    code, _, _, _ = request(follow, urljoin(FRONT, form["action"]), form["fields"])
    assert code == 200 and request(direct, FRONT + "/message")[0] == 302
    assert request(build_opener(NoRedirect()), FRONT + "/message", headers={"Cookie": "SESSION=" + current_session})[0] == 302
    print("CSRF-protected logout and rejection of the old server session: passed")


if __name__ == "__main__":
    main()
