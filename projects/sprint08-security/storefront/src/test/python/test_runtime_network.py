"""Изоляция сетей и ограниченное противодавление при проверке общих сессий."""

from contextlib import redirect_stdout
from email.message import Message
import importlib.util
import ipaddress
import io
import json
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
from urllib.error import HTTPError

specification = importlib.util.spec_from_file_location(
    "store_runtime", Path(__file__).resolve().parents[4] / "scripts/runtime.py")
store_runtime = importlib.util.module_from_spec(specification)
specification.loader.exec_module(store_runtime)

specification = importlib.util.spec_from_file_location(
    "session_verification", Path(__file__).resolve().parents[4] / "scripts/verify-sessions.py")
session_verification = importlib.util.module_from_spec(specification)
with patch.dict(sys.modules, {"runtime": store_runtime}), \
        patch.object(sys, "path", [str(Path(specification.origin).parent), *sys.path]):
    specification.loader.exec_module(session_verification)


class RuntimeNetworkTest(unittest.TestCase):
    def test_subnet_avoids_the_whole_occupied_supernet(self):
        networks = [{"IPAM": {"Config": None}},
                    {"IPAM": {"Config": [{"Subnet": "10.250.0.0/24"}, {"Subnet": "fd00::/64"}]}}]
        with patch.object(store_runtime.subprocess, "check_output", side_effect=["one\ntwo\n", json.dumps(networks)]):
            selected = ipaddress.ip_network(store_runtime.available_subnet())
        self.assertFalse(selected.overlaps(ipaddress.ip_network("10.250.0.0/24")))
        self.assertTrue(selected.subnet_of(ipaddress.ip_network("10.250.0.0/16")))
        self.assertGreaterEqual(selected.num_addresses - 2, 14)


class SessionPressureTest(unittest.TestCase):
    @staticmethod
    def rejected(code=503, retry_after="1", cookie=None):
        headers = Message()
        if retry_after is not None:
            headers["Retry-After"] = retry_after
        if cookie is not None:
            headers["Set-Cookie"] = cookie
        return HTTPError("http://fixture/account/login", code, "Rejected", headers, None)

    def pressure(self, failures, buyer_path="/cabinet"):
        buyer = SimpleNamespace(base="http://fixture", secrets_dir=Path("/unused"),
                                read=Mock(return_value=SimpleNamespace(url="http://fixture" + buyer_path)))
        with patch.object(session_verification, "MarketProbe") as visitor, \
                patch.object(session_verification.subprocess, "check_output", return_value="evicted_keys:1\n"), \
                redirect_stdout(io.StringIO()) as output:
            visitor.return_value.read.side_effect = [*failures, *([None] * 4000)]
            session_verification.memory_pressure({"container": "isolated-fixture"}, buyer)
        return output.getvalue(), buyer

    def test_pressure_accepts_a_bounded_explicit_rejection_and_still_checks_the_buyer(self):
        output, buyer = self.pressure([self.rejected()])
        self.assertIn("reached eviction", output)
        self.assertGreater(buyer.read.call_count, 0)

    def test_pressure_rejects_500_bad_backpressure_headers_and_sustained_outage(self):
        cases = (([self.rejected(code=500)], HTTPError),
                 ([self.rejected(retry_after=None)], AssertionError),
                 ([self.rejected(retry_after="2")], AssertionError),
                 ([self.rejected(cookie="SESSION=unstored")], AssertionError),
                 ([self.rejected() for _ in range(9)], AssertionError))
        for failures, expected in cases:
            with self.subTest(code=failures[0].code, count=len(failures)), self.assertRaises(expected):
                self.pressure(failures)

    def test_pressure_never_allows_the_authenticated_buyer_to_lose_access(self):
        with self.assertRaisesRegex(AssertionError, "active buyer lost access"):
            self.pressure([self.rejected()], buyer_path="/account/login")
