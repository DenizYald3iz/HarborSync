from __future__ import annotations

import io
import sys
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch


SERVICE_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVICE_DIR))

import simulate  # noqa: E402


class DroneSimulatorTests(unittest.TestCase):
    def test_generate_telemetry_contains_contract_fields(self) -> None:
        payload = simulate.generate_telemetry()

        self.assertIn(payload["droneId"], simulate.DRONES)
        self.assertIn(payload["sector"], simulate.SECTORS)
        self.assertGreaterEqual(payload["containerCount"], simulate.MIN_CONTAINERS)
        self.assertLessEqual(payload["containerCount"], simulate.MAX_CONTAINERS)
        self.assertEqual(payload["capacity"], simulate.CAPACITY)
        self.assertIsInstance(payload["blockageDetected"], bool)
        self.assertIn("timestamp", payload)
        self.assertIn("vesselEta", payload)

    def test_main_handles_unavailable_telemetry_service_once(self) -> None:
        payload = {
            "droneId": "HD-01",
            "sector": "A-01",
            "containerCount": 91,
            "capacity": 100,
            "blockageDetected": False,
            "timestamp": "2026-05-31T18:00:00+00:00",
            "vesselEta": None,
        }
        output = io.StringIO()

        with patch.object(simulate, "MAX_ITERATIONS", 1), patch.object(
            simulate, "generate_telemetry", return_value=payload
        ), patch.object(
            simulate,
            "send_telemetry",
            side_effect=simulate.requests.RequestException("connection refused"),
        ), redirect_stdout(output):
            simulate.main()

        self.assertIn("Drone simulator started.", output.getvalue())
        self.assertIn("telemetry service unavailable", output.getvalue())

    def test_send_telemetry_returns_status_code(self) -> None:
        with patch.object(simulate.requests, "post") as post:
            post.return_value.status_code = 202

            status_code = simulate.send_telemetry({"sector": "A-01"})

        self.assertEqual(status_code, 202)
        post.assert_called_once()
        _, kwargs = post.call_args
        self.assertEqual(post.call_args.args[0], simulate.TELEMETRY_URL)
        self.assertEqual(kwargs["json"], {"sector": "A-01"})
        self.assertEqual(kwargs["timeout"], simulate.REQUEST_TIMEOUT_SECONDS)
        self.assertRegex(kwargs["headers"]["X-Correlation-ID"], r"^sim-[0-9a-f]{8}$")


if __name__ == "__main__":
    unittest.main()
