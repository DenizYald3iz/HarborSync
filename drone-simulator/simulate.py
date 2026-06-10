from __future__ import annotations

import os
import random
import time
import uuid
from datetime import datetime, timezone
from typing import Any

import requests


TELEMETRY_URL = os.getenv(
    "TELEMETRY_URL",
    "http://localhost:8082/telemetry/ingest",
)
INTERVAL_SECONDS = float(os.getenv("SIM_INTERVAL_SECONDS", "2"))
REQUEST_TIMEOUT_SECONDS = float(os.getenv("REQUEST_TIMEOUT_SECONDS", "2"))

SECTORS = [
    value.strip()
    for value in os.getenv("SECTORS", "A-01,B-12,C-07,D-04").split(",")
    if value.strip()
]
DRONES = [
    value.strip()
    for value in os.getenv("DRONES", "HD-01,HD-07,HD-11").split(",")
    if value.strip()
]

CAPACITY = int(os.getenv("CAPACITY", "100"))
MIN_CONTAINERS = int(os.getenv("MIN_CONTAINERS", "70"))
MAX_CONTAINERS = int(os.getenv("MAX_CONTAINERS", "100"))
BLOCKAGE_PROBABILITY = float(os.getenv("BLOCKAGE_PROBABILITY", "0.15"))
ETA_PROBABILITY = float(os.getenv("ETA_PROBABILITY", "0.30"))
VESSEL_ETA = os.getenv("VESSEL_ETA", "14:30")
MAX_ITERATIONS = int(os.getenv("MAX_ITERATIONS", "0"))


def generate_telemetry() -> dict[str, Any]:
    return {
        "droneId": random.choice(DRONES),
        "sector": random.choice(SECTORS),
        "containerCount": random.randint(MIN_CONTAINERS, MAX_CONTAINERS),
        "capacity": CAPACITY,
        "blockageDetected": random.random() < BLOCKAGE_PROBABILITY,
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "vesselEta": VESSEL_ETA if random.random() < ETA_PROBABILITY else None,
    }


def send_telemetry(payload: dict[str, Any]) -> int | None:
    correlation_id = f"sim-{uuid.uuid4().hex[:8]}"
    response = requests.post(
        TELEMETRY_URL,
        json=payload,
        headers={"X-Correlation-ID": correlation_id},
        timeout=REQUEST_TIMEOUT_SECONDS,
    )
    return response.status_code


def main() -> None:
    print(
        "Drone simulator started. "
        f"url={TELEMETRY_URL} interval={INTERVAL_SECONDS:g}s"
    )

    iteration = 0
    while True:
        iteration += 1
        payload = generate_telemetry()
        try:
            status_code = send_telemetry(payload)
            print(
                f"[{payload['droneId']}] sector={payload['sector']} "
                f"containers={payload['containerCount']}/{payload['capacity']} "
                f"status={status_code}"
            )
        except requests.RequestException as exc:
            print(
                f"[{payload['droneId']}] sector={payload['sector']} "
                f"telemetry service unavailable: {exc}"
            )

        if MAX_ITERATIONS and iteration >= MAX_ITERATIONS:
            break

        time.sleep(INTERVAL_SECONDS)


if __name__ == "__main__":
    main()
