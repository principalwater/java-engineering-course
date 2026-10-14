#!/usr/bin/env python3
"""Проверяет настоящий HTTP-сеанс магазина; создаёт собственные товары и заказ в тестовом стенде."""

import argparse
from html.parser import HTMLParser
from http.cookiejar import CookieJar
import json
import re
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlencode, urlparse
from urllib.request import build_opener, HTTPCookieProcessor, HTTPRedirectHandler, Request
import uuid

CSRF_FIELD = "_csrf"


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
        self.feed(body)

    def handle_starttag(self, tag, attributes):
        attributes = dict(attributes)
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
    def __init__(self, base):
        self.base = base.rstrip("/")
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
        result = self.read(path, urlencode({CSRF_FIELD: page.token, **values}).encode(), "application/x-www-form-urlencoded")
        assert self.redirects.latest[0:2] == (303, "POST"), "Mutations must use POST/Redirect/GET"
        return result

    def run(self):
        marker = "C++ & " + uuid.uuid4().hex
        catalog = self.read("/catalog")
        boundary = "storefront-" + uuid.uuid4().hex
        products = [
            {"sku": "probe-" + uuid.uuid4().hex, "title": "Alpha " + marker, "description": "Shared description " + marker, "image": "KEYBOARD", "price": 100},
            {"sku": "probe-" + uuid.uuid4().hex, "title": "Beta " + marker, "description": "Shared description " + marker, "image": "CUP", "price": 200},
        ]
        payload = (f'--{boundary}\r\nContent-Disposition: form-data; name="{CSRF_FIELD}"\r\n\r\n{catalog.token}\r\n'
                   f'--{boundary}\r\nContent-Disposition: form-data; name="catalog"; filename="catalog.json"\r\n'
                   'Content-Type: application/json\r\n\r\n').encode() + json.dumps(products).encode() + f"\r\n--{boundary}--\r\n".encode()
        self.read("/catalog/import", payload, "multipart/form-data; boundary=" + boundary)
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
        return {"order_path": order_path, "cookies": self.cookies, "marker": marker}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8086")
    arguments = parser.parse_args()
    MarketProbe(arguments.base_url).run()
