"""Protect the HTTP startup boundary without starting an application process."""

from http.server import BaseHTTPRequestHandler, HTTPServer
import importlib.util
import json
from pathlib import Path
import threading
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("boot_launcher", Path(__file__).parents[1] / "verify-boot-actuator.py")
LAUNCHER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(LAUNCHER)


class StartupHealthTest(unittest.TestCase):
    def test_listening_port_requires_availability_not_an_aggregate_health_status(self):
        responses = [
            (503, {"status": "DOWN", "components": {"readinessState": {"status": "OUT_OF_SERVICE"}}}),
            (503, {"status": "DOWN", "components": {"readinessState": {"status": "UP"}, "cycleCheck": {"status": "DOWN"}}}),
            (200, {"status": "UP"}),
            (503, {"status": "DOWN"}),
        ]
        paths = []

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                paths.append(self.path)
                status, body = responses.pop(0)
                self.send_response(status)
                self.end_headers()
                self.wfile.write(json.dumps(body).encode())

            def log_message(self, *args):
                pass

        with HTTPServer(("127.0.0.1", 0), Handler) as server:
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            try:
                with patch.object(LAUNCHER, "PORT", server.server_port):
                    self.assertFalse(LAUNCHER.application_ready("probes"))
                    self.assertTrue(LAUNCHER.application_ready("probes"))
                    self.assertTrue(LAUNCHER.application_ready("default"))
                    self.assertFalse(LAUNCHER.application_ready("lab"))
            finally:
                server.shutdown()
                worker.join()
        self.assertEqual(paths, ["/actuator/health/readiness"] * 2 + ["/actuator/health"] * 2)


if __name__ == "__main__":
    unittest.main()
