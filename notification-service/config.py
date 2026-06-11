from __future__ import annotations

import os


RABBITMQ_URL = os.getenv("RABBITMQ_URL", "amqp://guest:guest@localhost:15673/")
RABBITMQ_EXCHANGE = os.getenv("RABBITMQ_EXCHANGE", "harborsync.exchange")
RABBITMQ_CONNECTION_RETRY_SECONDS = float(
    os.getenv("RABBITMQ_CONNECTION_RETRY_SECONDS", "5")
)
CONGESTION_ALERT_ROUTING_KEY = os.getenv(
    "CONGESTION_ALERT_ROUTING_KEY", "congestion.alert"
)

QUEUES = {
    "congestion_alert": os.getenv(
        "CONGESTION_ALERT_QUEUE", "congestion.alert.notification"
    ),
    "task_created": os.getenv("TASK_CREATED_QUEUE", "task.created"),
    "task_failed": os.getenv("TASK_FAILED_QUEUE", "task.failed"),
    "vessel_arrived": os.getenv("VESSEL_ARRIVED_QUEUE", "vessel.arrived"),
    "vessel_docked": os.getenv("VESSEL_DOCKED_QUEUE", "vessel.docked"),
    "vessel_departed": os.getenv("VESSEL_DEPARTED_QUEUE", "vessel.departed"),
    "dlq": os.getenv("DLQ_QUEUE", "dlq.errors"),
}

LOG_FILE = os.getenv("LOG_FILE", "logs/alerts.log")
LOG_MAX_BYTES = int(os.getenv("LOG_MAX_BYTES", "10000000"))
LOG_BACKUP_COUNT = int(os.getenv("LOG_BACKUP_COUNT", "5"))
