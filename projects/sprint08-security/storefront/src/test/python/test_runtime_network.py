"""Выбор подсети не пересекает сохранённые сети Docker и учитывает пустой IPAM/IPv6."""

import importlib.util
import ipaddress
import json
from pathlib import Path
import unittest
from unittest.mock import patch

specification = importlib.util.spec_from_file_location(
    "store_runtime", Path(__file__).resolve().parents[4] / "scripts/runtime.py")
store_runtime = importlib.util.module_from_spec(specification)
specification.loader.exec_module(store_runtime)


class RuntimeNetworkTest(unittest.TestCase):
    def test_subnet_avoids_the_whole_occupied_supernet(self):
        networks = [{"IPAM": {"Config": None}},
                    {"IPAM": {"Config": [{"Subnet": "10.250.0.0/24"}, {"Subnet": "fd00::/64"}]}}]
        with patch.object(store_runtime.subprocess, "check_output", side_effect=["one\ntwo\n", json.dumps(networks)]):
            selected = ipaddress.ip_network(store_runtime.available_subnet())
        self.assertFalse(selected.overlaps(ipaddress.ip_network("10.250.0.0/24")))
        self.assertTrue(selected.subnet_of(ipaddress.ip_network("10.250.0.0/16")))
        self.assertGreaterEqual(selected.num_addresses - 2, 14)
