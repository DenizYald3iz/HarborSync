from __future__ import annotations

import asyncio
import json
import sys
import types
import unittest
from pathlib import Path
from unittest.mock import patch


SERVICE_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVICE_DIR))

if "aio_pika" not in sys.modules:
    aio_pika_stub = types.ModuleType("aio_pika")
    aio_pika_stub.ExchangeType = types.SimpleNamespace(DIRECT="direct")
    aio_pika_stub.IncomingMessage = object
    aio_pika_stub.connect_robust = None

    aio_pika_abc_stub = types.ModuleType("aio_pika.abc")
    aio_pika_abc_stub.AbstractRobustConnection = object

    sys.modules["aio_pika"] = aio_pika_stub
    sys.modules["aio_pika.abc"] = aio_pika_abc_stub

import consumer  # noqa: E402


class FakeProcess:
    def __init__(self, message: "FakeMessage", requeue: bool) -> None:
        self.message = message
        self.requeue = requeue

    async def __aenter__(self) -> "FakeProcess":
        self.message.process_started = True
        self.message.requeue = self.requeue
        return self

    async def __aexit__(self, exc_type, exc, traceback) -> bool:
        self.message.process_finished = True
        return False


class FakeMessage:
    def __init__(
        self,
        payload: object,
        *,
        message_id: str | None = None,
        routing_key: str | None = None,
        correlation_id: str | None = None,
        headers: dict | None = None,
    ) -> None:
        if isinstance(payload, bytes):
            self.body = payload
        else:
            self.body = json.dumps(payload).encode("utf-8")
        self.message_id = message_id
        self.routing_key = routing_key
        self.correlation_id = correlation_id
        self.headers = headers
        self.process_started = False
        self.process_finished = False
        self.requeue: bool | None = None
        self.reject_called = False
        self.reject_requeue: bool | None = None

    def process(self, *, requeue: bool) -> FakeProcess:
        return FakeProcess(self, requeue)

    async def reject(self, requeue: bool = False) -> None:
        self.reject_called = True
        self.reject_requeue = requeue


class ConsumerTests(unittest.TestCase):
    def test_decode_payload_accepts_json_objects(self) -> None:
        message = FakeMessage({"correlationId": "CORR-1", "severity": "HIGH"})

        payload = consumer._decode_payload(message)

        self.assertEqual(payload["correlationId"], "CORR-1")
        self.assertEqual(payload["severity"], "HIGH")

    def test_decode_payload_rejects_invalid_payloads(self) -> None:
        with self.assertRaisesRegex(ValueError, "invalid JSON payload"):
            consumer._decode_payload(FakeMessage(b"{bad-json"))

        with self.assertRaisesRegex(ValueError, "payload must be a JSON object"):
            consumer._decode_payload(FakeMessage(["not", "an", "object"]))

    def test_handle_congestion_alert_logs_warning_and_acknowledges(self) -> None:
        message = FakeMessage(
            {
                "correlationId": "CORR-ALERT",
                "alertType": "SECTOR_CRITICAL",
                "sector": "B-12",
                "severity": "HIGH",
                "fillRate": 0.93,
                "recommendedAction": "Open overflow lane",
            }
        )
        log_entries: list[tuple[str, str | None, str]] = []

        with patch.object(
            consumer,
            "_log",
            side_effect=lambda level, correlation_id, message: log_entries.append(
                (level, correlation_id, message)
            ),
        ):
            asyncio.run(consumer.handle_congestion_alert(message))

        self.assertTrue(message.process_started)
        self.assertTrue(message.process_finished)
        self.assertTrue(message.requeue)
        self.assertEqual(log_entries[0][0], "warning")
        self.assertEqual(log_entries[0][1], "CORR-ALERT")
        self.assertIn("sector=B-12", log_entries[0][2])
        self.assertIn("fill_rate=93%", log_entries[0][2])

    def test_handle_congestion_alert_sends_to_dlq_after_max_attempts(self) -> None:
        message = FakeMessage(
            {"bad": "payload"},
            headers={"x-delivery-count": 3},
        )
        log_entries: list[tuple[str, str | None, str]] = []

        with patch.object(
            consumer,
            "_log",
            side_effect=lambda level, correlation_id, message: log_entries.append(
                (level, correlation_id, message)
            ),
        ):
            asyncio.run(consumer.handle_congestion_alert(message))

        self.assertTrue(message.reject_called)
        self.assertFalse(message.reject_requeue)
        self.assertIn("DLQ", log_entries[0][1])

    def test_handle_dlq_logs_message_metadata(self) -> None:
        message = FakeMessage(
            {"error": "bad payload"},
            message_id="MSG-1",
            routing_key="congestion.alert",
            correlation_id="CORR-DLQ",
        )
        log_entries: list[tuple[str, str | None, str]] = []

        with patch.object(
            consumer,
            "_log",
            side_effect=lambda level, correlation_id, message: log_entries.append(
                (level, correlation_id, message)
            ),
        ):
            asyncio.run(consumer.handle_dlq(message))

        self.assertEqual(log_entries[0][0], "error")
        self.assertEqual(log_entries[0][1], "CORR-DLQ")
        self.assertIn("message_id=MSG-1", log_entries[0][2])
        self.assertIn("routing_key=congestion.alert", log_entries[0][2])


    def test_handle_task_created_logs_info_and_acknowledges(self) -> None:
        message = FakeMessage(
            {
                "correlationId": "CORR-TASK",
                "taskId": "550e8400-e29b-41d4-a716-446655440000",
                "sector": "B-12",
                "assignedUnit": "Crane-1",
                "priority": "HIGH",
            }
        )
        log_entries: list[tuple[str, str | None, str]] = []

        with patch.object(
            consumer,
            "_log",
            side_effect=lambda level, correlation_id, message: log_entries.append(
                (level, correlation_id, message)
            ),
        ):
            asyncio.run(consumer.handle_task_created(message))

        self.assertTrue(message.process_started)
        self.assertTrue(message.process_finished)
        self.assertTrue(message.requeue)
        self.assertEqual(log_entries[0][0], "info")
        self.assertEqual(log_entries[0][1], "CORR-TASK")
        self.assertIn("task_id=550e8400-e29b-41d4-a716-446655440000", log_entries[0][2])
        self.assertIn("assigned_unit=Crane-1", log_entries[0][2])

    def test_handle_task_created_sends_to_dlq_after_max_attempts(self) -> None:
        message = FakeMessage(
            {"bad": "payload"},
            headers={"x-delivery-count": 3},
        )
        log_entries: list[tuple[str, str | None, str]] = []

        with patch.object(
            consumer,
            "_log",
            side_effect=lambda level, correlation_id, message: log_entries.append(
                (level, correlation_id, message)
            ),
        ):
            asyncio.run(consumer.handle_task_created(message))

        self.assertTrue(message.reject_called)
        self.assertFalse(message.reject_requeue)
        self.assertIn("DLQ", log_entries[0][1])


if __name__ == "__main__":
    unittest.main()
