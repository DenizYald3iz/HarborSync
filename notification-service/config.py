from __future__ import annotations

import os


RABBITMQ_URL = os.getenv("RABBITMQ_URL", "amqp://guest:guest@localhost:5672/")
RABBITMQ_EXCHANGE = os.getenv("RABBITMQ_EXCHANGE", "harborsync.exchange")
RABBITMQ_CONNECTION_RETRY_SECONDS = float(
    os.getenv("RABBITMQ_CONNECTION_RETRY_SECONDS", "5")
)

QUEUES = {
    "congestion_alert": os.getenv("CONGESTION_ALERT_QUEUE", "congestion.alert"),
    "task_created": os.getenv("TASK_CREATED_QUEUE", "task.created"),
    "dlq": os.getenv("DLQ_QUEUE", "dlq.errors"),
}

LOG_FILE = os.getenv("LOG_FILE", "logs/alerts.log")
LOG_MAX_BYTES = int(os.getenv("LOG_MAX_BYTES", "10000000"))
LOG_BACKUP_COUNT = int(os.getenv("LOG_BACKUP_COUNT", "5"))
