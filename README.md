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
