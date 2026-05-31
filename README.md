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

Copy `.env.example` to `.env` if you want to override defaults. The local
defaults are intentionally simple for the course demo.

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

## REST API

Gateway routes:

- `POST /api/vessels` -> Vessel Service `POST /vessels`
- `GET /api/vessels?status=ARRIVING` -> Vessel Service `GET /vessels`
- `GET /api/vessels/{id}` -> Vessel Service `GET /vessels/{id}`
- `PUT /api/vessels/{id}/status` -> Vessel Service `PUT /vessels/{id}/status`
- `GET /api/tasks/pending` -> Task Assignment `GET /tasks/pending`
- `PUT /api/tasks/{id}/complete` -> Task Assignment `PUT /tasks/{id}/complete`

Telemetry ingest is exposed directly on `8082` for the drone simulator:

- `POST /telemetry/ingest`

## Demo Flow

Start the platform:

```bash
docker compose up --build
```

Create an arriving vessel through the gateway:

```bash
curl -X POST http://localhost:8080/api/vessels \
  -H "Content-Type: application/json" \
  -d '{"name":"MV-Ankara","imoNumber":"IMO1234567","eta":"2025-01-15T14:00:00"}'
```

Send critical telemetry:

```bash
curl -X POST http://localhost:8082/telemetry/ingest \
  -H "Content-Type: application/json" \
  -H "X-Correlation-ID: OP-DEMO-001" \
  -d '{"droneId":"HD-07","sector":"B-12","containerCount":94,"capacity":100,"blockageDetected":true,"timestamp":"2025-01-15T14:28:00Z","vesselEta":"14:30"}'
```

Check pending tasks:

```bash
curl http://localhost:8080/api/tasks/pending
```

Check notification logs:

```bash
docker logs notification-service
```

Expected result: Congestion Analysis publishes `congestion.alert`, Task
Assignment creates a pending task and publishes `task.created`, and Notification
Service logs both business events with the same correlation ID.

## Tests

Python tests can run in this environment without extra infrastructure:

```bash
python3 -m unittest discover -s notification-service/tests -v
python3 -m unittest discover -s drone-simulator/tests -v
```

Java tests require Maven and Java 17:

```bash
mvn -f vessel-service/pom.xml test
mvn -f telemetry-service/pom.xml test
mvn -f congestion-analysis/pom.xml test
mvn -f task-assignment-service/pom.xml test
mvn -f gateway/pom.xml test
```

Static checks used during setup:

```bash
docker compose config --quiet
```

## Team Responsibilities

| Owner | Scope |
|---|---|
| Emirhan | API Gateway, Vessel Service, Congestion Analysis |
| Deniz | Telemetry Service, Task Assignment, Notification Service, Drone Simulator |
| Shared | Docker Compose, RabbitMQ contracts, demo flow, README/docs |

## Development Plan

| Week | Emirhan | Deniz |
|---|---|---|
| 1 | Vessel Service schema and CRUD | Telemetry Service ingest and Redis state |
| 1 | Docker Compose infrastructure | Docker Compose infrastructure |
| 2 | Congestion Analysis rule engine | Task Assignment persistence and alert consumer |
| 2 | API Gateway routing and correlation ID | Notification consumers and log formatter |
| 3 | Vessel and Congestion tests | Task Assignment circuit breaker, saga, drone simulator |
| 4 | End-to-end demo and docs | End-to-end demo and docs |

## Known Risks

- Authentication and authorization are not implemented; production use would
  need OAuth2/JWT or equivalent gateway-level auth.
- RabbitMQ, databases, and gateway run as single local instances; this is
  acceptable for course/demo scope but not high availability.
- Local secrets are plain environment variables. Production should use a secret
  manager.
- Observability is limited to structured logs and health endpoints; Prometheus,
  Grafana, tracing, or log aggregation can be added later.

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
