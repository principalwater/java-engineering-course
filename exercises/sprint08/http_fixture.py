"""Общие HTTP fixtures для локальных стендов спринта 8."""
from html.parser import HTMLParser
from http.cookiejar import CookieJar, DefaultCookiePolicy
from urllib.error import HTTPError
from urllib.parse import urlencode, urlsplit
from urllib.request import build_opener, HTTPRedirectHandler, HTTPCookieProcessor, Request


def assert_origin(url, allowed_origins):
    parts = urlsplit(url)
    assert parts.scheme == "http" and parts.hostname == "127.0.0.1" and parts.username is None and parts.password is None
    assert parts.scheme + "://" + parts.netloc in allowed_origins, "Only isolated loopback services may receive test data"


class AllowedRedirect(HTTPRedirectHandler):
    def __init__(self, allowed_origins):
        self.allowed_origins = allowed_origins

    def redirect_request(self, request, fp, code, message, headers, new_url):
        assert_origin(new_url, self.allowed_origins)
        return super().redirect_request(request, fp, code, message, headers, new_url)


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


def browser(allowed_origins):
    # Браузер принимает Secure-cookie на loopback HTTP; имитируем это только для 127.0.0.1.
    cookies = CookieJar(DefaultCookiePolicy(allowed_domains=("127.0.0.1",),
                                          secure_protocols=("https", "wss", "http")))
    return (build_opener(HTTPCookieProcessor(cookies), AllowedRedirect(allowed_origins)),
            build_opener(HTTPCookieProcessor(cookies), NoRedirect()))


def request(opener, url, fields=None, headers=None, *, allowed_origins):
    assert_origin(url, allowed_origins)
    body = urlencode(fields).encode() if fields is not None else None
    try:
        response = opener.open(Request(url, data=body, headers=headers or {}), timeout=5)
    except HTTPError as error:
        response = error
    with response:
        return response.status, response.headers, response.read().decode(), response.url
