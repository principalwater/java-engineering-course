"""Protect the wire check from treating an open or malformed CQL socket as ready."""

import importlib.util
from pathlib import Path
import socket
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("cassandra_probe", Path(__file__).with_name("probe-cassandra.py"))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class CassandraProbeTest(unittest.TestCase):
    def test_accepts_supported_but_rejects_errors_truncation_and_unbounded_frames(self):
        body = b"\x00\x01\x00\x0bCQL_VERSION\x00\x01\x00\x053.0.0"
        supported = bytes.fromhex("84 00 00 00 06") + len(body).to_bytes(4, "big") + body
        for response, valid in ((supported, True), (b"", False),
                                (bytes.fromhex("84 00 00 00 00 00 00 00 00"), False),
                                (supported[:-1], False),
                                (bytes.fromhex("84 00 00 00 06 00 01 00 01"), False)):
            with self.subTest(response=response):
                client, server = socket.socketpair()
                with server, client:
                    server.sendall(response)
                    server.shutdown(socket.SHUT_WR)
                    with patch.object(probe.socket, "create_connection", return_value=client):
                        if valid:
                            probe.probe()
                        else:
                            with self.assertRaises(RuntimeError):
                                probe.probe()


if __name__ == "__main__":
    unittest.main()
