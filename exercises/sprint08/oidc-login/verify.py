#!/usr/bin/env python3
"""Проверяет реальный Authorization Code flow, CSRF и локальный logout."""
from html.parser import HTMLParser
from http.cookiejar import CookieJar, DefaultCookiePolicy
import json
from pathlib import Path
import time
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlencode, urljoin, urlsplit, urlunsplit
from urllib.request import build_opener, HTTPRedirectHandler, HTTPCookieProcessor, Request

APP = "http://127.0.0.1:18180"
ISSUER = "http://127.0.0.1:18280/realms/study"


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        return None


class Forms(HTMLParser):
    def __init__(self, text):
        super().__init__()
        self.forms = []
        self.current = None
        self.feed(text)

    def handle_starttag(self, tag, attrs):
        values = dict(attrs)
        if tag == "form":
            self.current = {"id": values.get("id"), "action": values.get("action"), "fields": {}}
            self.forms.append(self.current)
        elif tag == "input" and self.current is not None and "name" in values:
            self.current["fields"][values["name"]] = values.get("value", "")

    def handle_endtag(self, tag):
        if tag == "form":
            self.current = None


def browser():
    # Браузер принимает Secure-cookie на loopback HTTP; имитируем это только для 127.0.0.1.
    cookies = CookieJar(DefaultCookiePolicy(allowed_domains=("127.0.0.1",),
                                          secure_protocols=("https", "wss", "http")))
    return (build_opener(HTTPCookieProcessor(cookies)),
            build_opener(HTTPCookieProcessor(cookies), NoRedirect()))


def request(opener, url, fields=None, headers=None):
    assert urlsplit(url).scheme == "http" and urlsplit(url).netloc in {"127.0.0.1:18180", "127.0.0.1:18280"}, "Only isolated loopback services may receive test data"
    body = urlencode(fields).encode() if fields is not None else None
    try:
        response = opener.open(Request(url, data=body, headers=headers or {}), timeout=5)
    except HTTPError as error:
        response = error
    with response:
        return response.status, response.headers, response.read().decode(), response.url


def login_form(opener):
    code, _, body, _ = request(opener, APP + "/api/profile")
    assert code == 200, "Expected the provider login page"
    return next(form for form in Forms(body).forms if form["id"] == "kc-form-login")


def main():
    values = dict(line.split("=", 1) for line in
                  (Path(__file__).resolve().parent / "target/lab.env").read_text().splitlines())
    follow, direct = browser()
    deadline = time.monotonic() + 90
    while True:
        try:
            code, _, _, _ = request(direct, APP + "/")
            assert code == 200, "Public home must be available"
            break
        except URLError:
            if time.monotonic() >= deadline:
                raise RuntimeError("Application did not become ready") from None
            time.sleep(0.2)

    code, headers, _, _ = request(direct, APP + "/api/profile")
    assert code == 302, "Anonymous profile request must start login"
    code, headers, _, _ = request(direct, urljoin(APP, headers["Location"]))
    parameters = parse_qs(urlsplit(headers["Location"]).query)
    assert code == 302 and parameters["response_type"] == ["code"]
    assert parameters["code_challenge_method"] == ["S256"]
    assert len(parameters["code_challenge"][0]) == 43 and parameters["state"]
    print("Anonymous access and PKCE S256: passed")

    form = login_form(follow)
    form["fields"].update(username="alice", password="incorrect-password")
    code, _, body, _ = request(follow, form["action"], form["fields"])
    assert code == 200 and any(f["id"] == "kc-form-login" for f in Forms(body).forms)
    assert request(direct, APP + "/api/profile")[0] == 302
    form = next(f for f in Forms(body).forms if f["id"] == "kc-form-login")
    form["fields"].update(username="alice", password=values["COURSE_USER_PASSWORD"])
    code, _, body, _ = request(follow, form["action"], form["fields"])
    profile = json.loads(body)
    assert code == 200 and profile["username"] == "alice" and profile["subject"]
    assert profile["issuer"] == ISSUER and set(profile) == {"subject", "username", "issuer"}
    print("Wrong password rejected; OIDC identity verified without token exposure: passed")

    # Подменяем state у настоящего выданного code, чтобы отказ не объяснялся фиктивным code.
    other_follow, other_direct = browser()
    form = login_form(other_follow)
    form["fields"].update(username="alice", password=values["COURSE_USER_PASSWORD"])
    code, headers, _, _ = request(other_direct, form["action"], form["fields"])
    callback = urlsplit(headers["Location"])
    parameters = parse_qs(callback.query)
    assert code == 302 and callback.path == "/login/oauth2/code/keycloak" and parameters["code"]
    parameters["state"] = ["tampered"]
    bad_callback = urlunsplit(callback._replace(query=urlencode(parameters, doseq=True)))
    request(other_follow, bad_callback)
    assert request(other_direct, APP + "/api/profile")[0] == 302
    print("Tampered state with a real authorization code: rejected")

    assert request(direct, APP + "/logout", {})[0] == 403, "Logout must require CSRF"
    code, _, body, _ = request(follow, APP + "/profile")
    form = next(f for f in Forms(body).forms if urlsplit(f["action"]).path == "/logout")
    assert code == 200 and form["fields"].get("_csrf")
    cookies = next(handler.cookiejar for handler in direct.handlers if isinstance(handler, HTTPCookieProcessor))
    old_session = next(cookie.value for cookie in cookies if cookie.name == "JSESSIONID")
    code, _, _, _ = request(follow, urljoin(APP, form["action"]), form["fields"])
    assert code == 200 and request(direct, APP + "/api/profile")[0] == 302
    stale_browser = build_opener(NoRedirect())
    assert request(stale_browser, APP + "/api/profile", headers={"Cookie": "JSESSIONID=" + old_session})[0] == 302
    print("CSRF protected local logout; old server session cannot access profile: passed")


if __name__ == "__main__":
    main()
