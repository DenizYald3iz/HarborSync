from __future__ import annotations

import asyncio
import json
from typing import Any

import aio_pika
from aio_pika.abc import AbstractRobustConnection

from config import (
    QUEUES,
    RABBITMQ_CONNECTION_RETRY_SECONDS,
    RABBITMQ_EXCHANGE,
    RABBITMQ_URL,
)
from formatter import get_logger


logger = get_logger("notification.consumer")


def _log(level: str, correlation_id: str | None, message: str) -> None:
    extra = {"correlation_id": correlation_id or "N/A"}
    getattr(logger, level)(message, extra=extra)


def _decode_payload(message: aio_pika.IncomingMessage) -> dict[str, Any]:
    try:
        payload = json.loads(message.body.decode("utf-8"))
    except json.JSONDecodeError as exc:
        raise ValueError(f"invalid JSON payload: {exc}") from exc

    if not isinstance(payload, dict):
        raise ValueError("payload must be a JSON object")

    return payload


MAX_DELIVERY_ATTEMPTS = 3


def _delivery_count(message: aio_pika.IncomingMessage) -> int:
    count = message.headers.get("x-delivery-count") if message.headers else None
    return (count if isinstance(count, int) else 0) + 1


async def _handle_with_retry(
    message: aio_pika.IncomingMessage,
    handler_name: str,
    process_fn: callable,
) -> None:
    delivery = _delivery_count(message)
    if delivery >= MAX_DELIVERY_ATTEMPTS:
        await message.reject(requeue=False)
        _log(
            "error",
            message.correlation_id or "DLQ",
            f"{handler_name} failed after {delivery} attempts, sent to DLQ",
        )
        return

    async with message.process(requeue=True):
        try:
            payload = _decode_payload(message)
            process_fn(payload)
        except Exception as exc:
            _log(
                "error",
                "ERR",
                f"{handler_name} attempt {delivery}/{MAX_DELIVERY_ATTEMPTS}: {exc}",
            )
            raise


def _process_congestion_alert(payload: dict) -> None:
    correlation_id = payload.get("correlationId")
    alert_type = payload.get("alertType", "UNKNOWN")
    sector = payload.get("sector", "?")
    severity = payload.get("severity", "?")
    fill_rate = payload.get("fillRate")
    recommended_action = payload.get("recommendedAction", "?")

    fill_percent = "unknown"
    if isinstance(fill_rate, (int, float)):
        fill_percent = f"{fill_rate * 100:.0f}%"

    _log(
        "warning",
        correlation_id,
        (
            f"congestion.alert alert_type={alert_type} severity={severity} "
            f"sector={sector} fill_rate={fill_percent} "
            f"recommended_action={recommended_action}"
        ),
    )


def _process_task_created(payload: dict) -> None:
    correlation_id = payload.get("correlationId")
    task_id = payload.get("taskId", "?")
    sector = payload.get("sector", "?")
    assigned_unit = payload.get("assignedUnit", "?")
    priority = payload.get("priority", "?")

    _log(
        "info",
        correlation_id,
        (
            f"task.created task_id={task_id} sector={sector} "
            f"assigned_unit={assigned_unit} priority={priority}"
        ),
    )


async def handle_congestion_alert(message: aio_pika.IncomingMessage) -> None:
    await _handle_with_retry(message, "congestion.alert", _process_congestion_alert)


async def handle_task_created(message: aio_pika.IncomingMessage) -> None:
    await _handle_with_retry(message, "task.created", _process_task_created)


async def handle_dlq(message: aio_pika.IncomingMessage) -> None:
    async with message.process(requeue=False):
        message_id = message.message_id or "unknown"
        routing_key = message.routing_key or "unknown"
        body_preview = message.body.decode("utf-8", errors="replace")[:500]
        _log(
            "error",
            message.correlation_id or "DLQ",
            (
                f"dlq.errors message_id={message_id} routing_key={routing_key} "
                f"body={body_preview}"
            ),
        )


async def _connect_with_retry() -> AbstractRobustConnection:
    while True:
        try:
            connection = await aio_pika.connect_robust(RABBITMQ_URL)
            _log("info", "INIT", "connected to RabbitMQ")
            return connection
        except Exception as exc:
            _log(
                "error",
                "INIT",
                (
                    "RabbitMQ connection failed; retrying in "
                    f"{RABBITMQ_CONNECTION_RETRY_SECONDS:g}s: {exc}"
                ),
            )
            await asyncio.sleep(RABBITMQ_CONNECTION_RETRY_SECONDS)


async def start_consumers() -> AbstractRobustConnection:
    connection = await _connect_with_retry()
    channel = await connection.channel()
    await channel.set_qos(prefetch_count=10)

    exchange = await channel.declare_exchange(
        RABBITMQ_EXCHANGE,
        aio_pika.ExchangeType.DIRECT,
        durable=True,
    )

    dlq_queue = await channel.declare_queue(QUEUES["dlq"], durable=True)
    dead_letter_arguments = {
        "x-dead-letter-exchange": "",
        "x-dead-letter-routing-key": QUEUES["dlq"],
    }
    congestion_queue = await channel.declare_queue(
        QUEUES["congestion_alert"],
        durable=True,
        arguments=dead_letter_arguments,
    )
    task_queue = await channel.declare_queue(
        QUEUES["task_created"],
        durable=True,
        arguments=dead_letter_arguments,
    )

    await congestion_queue.bind(exchange, routing_key=QUEUES["congestion_alert"])
    await task_queue.bind(exchange, routing_key=QUEUES["task_created"])
    await dlq_queue.bind(exchange, routing_key=QUEUES["dlq"])

    await congestion_queue.consume(handle_congestion_alert)
    await task_queue.consume(handle_task_created)
    await dlq_queue.consume(handle_dlq)

    _log(
        "info",
        "INIT",
        (
            "Notification Service consumers started for queues "
            f"{QUEUES['congestion_alert']}, {QUEUES['task_created']}, {QUEUES['dlq']}"
        ),
    )
    return connection
