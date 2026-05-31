# HarborSync

Distributed Port Cargo Coordination Platform for CENG-442 Microservice Architecture.

## Services

| Service | Owner | Technology | Port |
|---|---|---|---|
| API Gateway | Emirhan | Spring Cloud Gateway | 8080 |
| Vessel Service | Emirhan | Spring Boot 3 + PostgreSQL | 8081 |
| Telemetry Service | Deniz | Spring Boot 3 + Redis + RabbitMQ | 8082 |
| Congestion Analysis | Emirhan | Spring Boot 3 + RabbitMQ | 8083 |
| Task Assignment | Deniz | Spring Boot 3 + PostgreSQL + RabbitMQ | 8084 |
| Notification Service | Deniz | FastAPI + RabbitMQ | 8085 |

## Local Stack

```bash
docker compose up --build
```

RabbitMQ Management UI: http://localhost:15672

Default credentials are `guest` / `guest`.

## Workflow

1. Drone simulator or REST client sends raw telemetry.
2. Telemetry Service validates the payload, stores latest drone state in Redis, and publishes `telemetry.processed`.
3. Congestion Analysis evaluates congestion rules and publishes `congestion.alert`.
4. Task Assignment consumes alerts, calls Vessel Service when needed, creates tasks, and publishes `task.created`.
5. Notification Service consumes business events and writes structured logs.

See [event contracts](docs/event-contracts.md) for queue payloads.

## Notification Service

The FastAPI notification service listens to `congestion.alert`, `task.created`, and
`dlq.errors` over RabbitMQ. It writes rotating structured logs to
`notification-service/logs/alerts.log` with timestamp, level, correlation ID, and
message fields.

```bash
cd notification-service
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8085
```

Health check: http://localhost:8085/health

## Drone Simulator

The simulator posts raw telemetry payloads to the Telemetry Service and continues
looping if the endpoint is temporarily unavailable.

```bash
cd drone-simulator
pip install requests
TELEMETRY_URL=http://localhost:8082/telemetry/ingest SIM_INTERVAL_SECONDS=2 python simulate.py
```
