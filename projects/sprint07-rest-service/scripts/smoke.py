#!/usr/bin/env python3
"""Проверяет настоящий HTTP-сеанс магазина; создаёт собственные товары и заказ в тестовом стенде."""

import argparse
from html.parser import HTMLParser
from http.cookiejar import CookieJar
import json
import os
from pathlib import Path
import re
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlencode, urlparse
from urllib.request import build_opener, HTTPCookieProcessor, HTTPRedirectHandler, Request
import uuid

CSRF_FIELD = "_csrf"
PROJECT = Path(__file__).resolve().parents[1]


class Page(HTMLParser):
    def __init__(self, url, body):
        super().__init__()
        self.url = url
        self.token = None
        self.products = set()
        self.links = set()
        self.cart_total = None
        self.order_total = None
        self.metrics = {}
        self.current_metric = None
        self.checkout_key = None
        self.amount_minor = None
        self.feed(body)

    def handle_starttag(self, tag, attributes):
        attributes = dict(attributes)
        if tag == "input" and attributes.get("name") == "checkoutKey":
            self.checkout_key = attributes.get("value")
        if tag == "input" and attributes.get("name") == "amountMinor":
            self.amount_minor = attributes.get("value")
        if tag == "input" and attributes.get("name") == CSRF_FIELD:
            self.token = attributes.get("value")
        if tag == "a":
            path = urlparse(attributes.get("href", "")).path
            self.links.add(path)
            if re.fullmatch(r"/items/\d+", path):
                self.products.add(int(path.rsplit("/", 1)[1]))
        if "data-cart-total" in attributes:
            self.cart_total = int(attributes["data-cart-total"])
        if "data-order-total" in attributes:
            self.order_total = int(attributes["data-order-total"])
        if "data-metric" in attributes:
            self.current_metric = attributes["data-metric"]

    def handle_data(self, data):
        if self.current_metric:
            self.metrics[self.current_metric] = self.metrics.get(self.current_metric, "") + data

    def handle_endtag(self, tag):
        if tag == "strong":
            self.current_metric = None


class Redirects(HTTPRedirectHandler):
    def __init__(self):
        self.latest = None

    def redirect_request(self, request, file, code, message, headers, target):
        self.latest = (code, request.get_method(), target)
        return super().redirect_request(request, file, code, message, headers, target)


class MarketProbe:
    def __init__(self, base, payments_url=None, secrets_dir=None):
        self.base = base.rstrip("/")
        self.payments_url = payments_url.rstrip("/") if payments_url else None
        self.secrets_dir = Path(secrets_dir or os.environ.get("STORE_SECRETS_DIR", os.environ.get("SECRETS_DIR", Path.home() / ".config/java-engineering-course/sprint07")))
        self.cookies = CookieJar()
        self.redirects = Redirects()
        self.http = build_opener(HTTPCookieProcessor(self.cookies), self.redirects)

    def read(self, path, data=None, content_type=None):
        headers = {"Accept": "text/html"}
        if content_type is not None:
            headers["Content-Type"] = content_type
        with self.http.open(Request(self.base + path, data=data, headers=headers), timeout=15) as response:
            assert response.status == 200
            page = Page(response.url, response.read().decode())
        assert "/orders" in page.links and "/cart/items" in page.links, "Every page must retain orders and cart navigation"
        return page

    def post(self, path, page, **values):
        assert page.token, "The rendered form must contain a real CSRF token"
        self.redirects.latest = None
        if path == "/buy":
            assert page.checkout_key and page.amount_minor, "Purchase form must carry its exact quote"
            values.update(checkoutKey=page.checkout_key, amountMinor=page.amount_minor)
        result = self.read(path, urlencode({CSRF_FIELD: page.token, **values}).encode(), "application/x-www-form-urlencoded")
        assert self.redirects.latest[0:2] == (303, "POST"), "Mutations must use POST/Redirect/GET"
        return result

    def login(self, username, password):
        page = self.read("/account/login")
        result = self.read("/account/login", urlencode({CSRF_FIELD: page.token, "username": username,
                           "password": password}).encode(), "application/x-www-form-urlencoded")
        assert urlparse(result.url).path == "/profile", "Account login failed"
        return result

    def json(self, path):
        with self.http.open(Request(self.base + path, headers={"Accept": "application/json"}), timeout=15) as response:
            return json.load(response)

    def verify_payment(self, proof):
        assert self.payments_url, "A payment service origin is required"
        token = (self.secrets_dir / "payments_service_token").read_text().strip()
        headers = {"Accept": "application/json", "Authorization": "Bearer " + token}
        balance_url = self.payments_url + "/api/v1/accounts/" + proof["account_id"] + "/balance"
        with self.http.open(Request(balance_url, headers=headers), timeout=10) as response:
            before = json.load(response)
        payload = json.dumps({"accountId": proof["account_id"], "referenceId": proof["payment_key"],
                              "amountMinor": proof["payment_amount_minor"], "currency": proof["currency"]}).encode()
        request = Request(self.payments_url + "/api/v1/payments", data=payload,
                          headers={**headers, "Content-Type": "application/json", "Idempotency-Key": proof["payment_key"]})
        with self.http.open(request, timeout=10) as response:
            assert response.status == 200, "A confirmed debit replay must not be created again"
            receipt = json.load(response)
        assert receipt["paymentId"] == proof["payment_id"] and receipt["status"] == "SUCCEEDED"
        assert receipt["accountId"] == proof["account_id"] and receipt["referenceId"] == proof["payment_key"]
        assert receipt["amountMinor"] == proof["payment_amount_minor"] and receipt["currency"] == proof["currency"]
        with self.http.open(Request(balance_url, headers=headers), timeout=10) as response:
            after = json.load(response)
        assert after == before, "Payment replay must not debit the account again"
        if "balance_minor" in proof:
            assert before["balanceMinor"] == proof["balance_minor"], "Durable payment balance changed across restart"
        return receipt

    def run(self):
        marker = "C++ & " + uuid.uuid4().hex
        # Импорт принадлежит продавцу; покупки проверяются отдельным аккаунтом и cookie jar.
        seller = MarketProbe(self.base, self.payments_url, self.secrets_dir)
        seller.login("seller", (self.secrets_dir / "seller_password").read_text().strip())
        catalog = seller.read("/catalog")
        boundary = "storefront-" + uuid.uuid4().hex
        products = [
            {"sku": "probe-" + uuid.uuid4().hex, "title": "Alpha " + marker, "description": "Shared description " + marker, "image": "KEYBOARD", "price": 100},
            {"sku": "probe-" + uuid.uuid4().hex, "title": "Beta " + marker, "description": "Shared description " + marker, "image": "CUP", "price": 200},
        ]
        payload = (f'--{boundary}\r\nContent-Disposition: form-data; name="{CSRF_FIELD}"\r\n\r\n{catalog.token}\r\n'
                   f'--{boundary}\r\nContent-Disposition: form-data; name="catalog"; filename="catalog.json"\r\n'
                   'Content-Type: application/json\r\n\r\n').encode() + json.dumps(products).encode() + f"\r\n--{boundary}--\r\n".encode()
        seller.read("/catalog/import", payload, "multipart/form-data; boundary=" + boundary)
        username = "probe-" + uuid.uuid4().hex[:16]
        password = "Probe-" + uuid.uuid4().hex
        page = self.read("/account/register")
        self.read("/account/register", urlencode({CSRF_FIELD: page.token, "username": username,
                  "displayName": "HTTP probe", "password": password}).encode(), "application/x-www-form-urlencoded")
        self.login(username, password)
        query = urlencode({"search": marker, "sort": "PRICE", "pageNumber": 1, "pageSize": 2})
        listing = self.read("/items?" + query)
        assert len(listing.products) == 2
        first, second = sorted(listing.products)
        listing = self.post("/items", listing, id=first, action="PLUS", search=marker, sort="PRICE", pageNumber=1, pageSize=2)
        assert parse_qs(urlparse(listing.url).query)["search"] == [marker]
        item = self.read(f"/items/{first}")
        self.post(f"/items/{first}", item, action="PLUS")
        cart = self.read("/cart/items")
        cart = self.post("/cart/items", cart, id=second, action="PLUS")
        assert cart.cart_total == 400
        cart = self.post("/cart/items", cart, id=first, action="MINUS")
        assert cart.cart_total == 300
        cart = self.post("/cart/items", cart, id=first, action="PLUS")
        cart = self.post("/cart/items", cart, id=second, action="DELETE")
        assert cart.cart_total == 200
        cart = self.post("/cart/items", cart, id=first, action="SET", quantity=3)
        assert cart.cart_total == 300
        cart = self.post("/cart/items", cart, id=first, action="SET", quantity=2)
        assert cart.cart_total == 200
        cart = self.post("/cart/items", cart, id=second, action="PLUS")
        try:
            self.read("/buy", b"", "application/x-www-form-urlencoded")
        except HTTPError as error:
            assert error.code == 403
            denied = Page(error.url, error.read().decode())
            assert "/orders" in denied.links and "/cart/items" in denied.links
        else:
            raise AssertionError("A purchase without CSRF must be rejected")
        order = self.post("/buy", cart)
        assert order.order_total == 400 and "newOrder=true" in order.url
        order_path = urlparse(order.url).path
        assert self.read(order_path).order_total == 400
        self.read("/orders")
        profile = self.read("/profile")
        assert "/profile/analytics" in profile.links
        assert not self.read("/cart/items").products
        self.read("/analytics")
        print("HTTP: catalog upload, literal search, quantity controls, CSRF, order snapshot, profile and navigation passed")
        proof = self.json(order_path + "/payment")
        assert proof["status"] == "PAID" and proof["currency"] == "RUB" and proof["amountMinor"] == 40000
        result = {"order_path": order_path, "cookies": self.cookies, "marker": marker,
                  "account_id": proof["accountId"], "payment_id": proof["paymentId"],
                  "payment_key": proof["referenceId"], "payment_amount_minor": proof["amountMinor"],
                  "currency": proof["currency"]}
        if self.payments_url:
            receipt = self.verify_payment(result)
            result["balance_minor"] = receipt["balanceMinor"]
        return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8087")
    parser.add_argument("--payments-url")
    parser.add_argument("--secrets-dir", type=Path)
    arguments = parser.parse_args()
    MarketProbe(arguments.base_url, arguments.payments_url, arguments.secrets_dir).run()
