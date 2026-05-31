from __future__ import annotations

from contextlib import asynccontextmanager
from typing import AsyncIterator

from fastapi import FastAPI

from consumer import start_consumers
from formatter import get_logger


logger = get_logger("notification.main")


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    connection = await start_consumers()
    app.state.rabbitmq_connection = connection

    try:
        yield
    finally:
        await connection.close()
        logger.info(
            "Notification Service RabbitMQ connection closed.",
            extra={"correlation_id": "SHUTDOWN"},
        )


app = FastAPI(title="HarborSync Notification Service", lifespan=lifespan)


@app.get("/health")
def health() -> dict[str, str | bool]:
    connection = getattr(app.state, "rabbitmq_connection", None)
    rabbitmq_connected = bool(connection and not connection.is_closed)
    return {
        "status": "UP",
        "service": "notification-service",
        "rabbitmqConnected": rabbitmq_connected,
    }
