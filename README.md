# HarborSync

Distributed Port Cargo Coordination Platform for CENG-442 Microservice Architecture.

## Services

| Service | Owner | Technology | Port |
|---|---|---|---|
| API Gateway | Emirhan | Spring Cloud Gateway | 8080 |
| Vessel Service | Emirhan | Spring Boot 3 + PostgreSQL | 8081 |
| Telemetry Service | Deniz | Go 1.22 + Redis + RabbitMQ | 8082 |
| Congestion Analysis | Emirhan | Spring Boot 3 + RabbitMQ | 8083 |
| Task Assignment | Deniz | Spring Boot 3 + PostgreSQL + RabbitMQ | 8084 |
| Notification Service | Deniz | FastAPI + RabbitMQ | 8085 |

## Architecture Diagram

```mermaid
flowchart TB
    subgraph External["External Input"]
        DS[Drone Simulator]
        RC[REST Client / Postman]
    end

    subgraph Gateway["API Gateway Layer"]
        GW[API Gateway<br/>Spring Cloud Gateway<br/>Port 8080]
        AF[Auth Filter<br/>Bearer JWT]
        CF[Correlation ID Filter]
        RL[Rate Limiter<br/>10 req/s, burst 20]
    end

    subgraph Services["Microservices Layer"]
        VS[Vessel Service<br/>Spring Boot 3<br/>Port 8081]
        TS[Telemetry Service<br/>Go 1.22<br/>Port 8082]
        CA[Congestion Analysis<br/>Spring Boot 3<br/>Port 8083]
        TA[Task Assignment<br/>Spring Boot 3<br/>Port 8084]
        NS[Notification Service<br/>FastAPI<br/>Port 8085]
    end

    subgraph Data["Data Layer"]
        PGV[(PostgreSQL<br/>vessel_db)]
        PGT[(PostgreSQL<br/>task_db)]
        RD[(Redis)]
        LOG[alerts.log]
    end

    subgraph Broker["Message Broker"]
        RMQ[RabbitMQ<br/>harborsync.exchange]
        Q1[telemetry.processed]
        Q2[congestion.alert routing key]
        Q2A[congestion.alert.task-assignment]
        Q2B[congestion.alert.notification]
        Q3[task.created]
        Q4[dlq.errors]
    end

    DS -- HTTP POST /telemetry/ingest --> TS
    RC -- HTTP --> GW
    GW --> AF --> CF --> RL
    RL --> VS
    RL --> TA
    TS --> Q1 --> CA
    CA --> Q2
    Q2 --> Q2A --> TA
    Q2 --> Q2B --> NS
    TA --> Q3 --> NS
    TA -- REST GET /vessels --> VS
    TA --> PGT
    VS --> PGV
    TS --> RD
    NS --> LOG
    RMQ --- Q1 & Q2 & Q3 & Q4
```

## Local Stack

### Prerequisites

For the normal Docker workflow you only need:

- Docker Engine
- Docker Compose plugin (`docker compose`)

For running services/tests without Docker:

- Java 17 + Maven 3.9.x for Spring Boot services
- Go 1.22 for Telemetry Service
- Python 3.11 for Notification Service and Drone Simulator

### Environment

Create a local `.env` file from the example:

```bash
cp .env.example .env
```

The default values are demo-friendly and match `docker-compose.yml`.

Important local defaults:

| Variable | Default | Used by |
|---|---|---|
| `RABBITMQ_DEFAULT_USER` | `guest` | RabbitMQ |
| `RABBITMQ_DEFAULT_PASS` | `guest` | RabbitMQ |
| `POSTGRES_USER` | `harbor` | PostgreSQL |
| `POSTGRES_PASSWORD` | `harbor123` | PostgreSQL |
| `VESSEL_DB` | `vessel_db` | Vessel DB |
| `TASK_DB` | `task_db` | Task DB |
| `REDIS_ADDR` | `redis:6379` | Telemetry Service |
| `RABBITMQ_URL` | `amqp://guest:guest@rabbitmq:5672/` | Go/Python services inside Docker |

### Run Everything

```bash
docker compose up --build
```

RabbitMQ Management UI: http://localhost:15672

Default credentials are `guest` / `guest`.

Host port mappings used by `docker-compose.yml`:

- RabbitMQ AMQP: `localhost:15673` -> container `5672`
- Vessel PostgreSQL: `localhost:15433` -> container `5432`
- Task PostgreSQL: `localhost:15434` -> container `5432`

Health endpoints:

- API Gateway: http://localhost:8080/actuator/health
- Vessel Service: http://localhost:8081/actuator/health
- Telemetry Service: http://localhost:8082/health
- Congestion Analysis: http://localhost:8083/actuator/health
- Task Assignment: http://localhost:8084/actuator/health
- Notification Service: http://localhost:8085/health

Useful Docker commands:

```bash
docker compose ps
docker compose logs -f telemetry-service
docker compose logs -f notification-service
docker compose down
docker compose down -v
```

Use `docker compose down -v` when you want to reset PostgreSQL/RabbitMQ/Redis
state completely.

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
- `PUT /api/vessels/{id}/berth/reserve` -> Vessel Service `PUT /vessels/{id}/berth/reserve`
- `PUT /api/vessels/{id}/berth/release` -> Vessel Service `PUT /vessels/{id}/berth/release`
- `GET /api/tasks/pending` -> Task Assignment `GET /tasks/pending`
- `PUT /api/tasks/{id}/complete` -> Task Assignment `PUT /tasks/{id}/complete`

Telemetry ingest is exposed directly on `8082` for the drone simulator:

- `POST /telemetry/ingest`

Gateway API routes require `Authorization: Bearer <HS256 JWT>` signed with
`HARBORSYNC_JWT_SECRET` (`harborsync-demo-jwt-secret` by default). Direct service
ports are intended for local development and do not enforce gateway auth.

## Demo Flow

Start the platform:

```bash
docker compose up --build
```

Create an arriving vessel through the gateway:

```bash
curl -X POST http://localhost:8080/api/vessels \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <demo-jwt>" \
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
curl http://localhost:8080/api/tasks/pending \
  -H "Authorization: Bearer <demo-jwt>"
```

Check notification logs:

```bash
docker logs notification-service
```

Expected result: Congestion Analysis publishes `congestion.alert`, Task
Assignment creates a pending task and publishes `task.created`, and Notification
Service logs both business events with the same correlation ID.

## Run Individual Services Locally

Infrastructure only:

```bash
docker compose up rabbitmq postgres-vessel postgres-task redis
```

Vessel Service:

```bash
mvn -f vessel-service/pom.xml spring-boot:run
```

Telemetry Service:

```bash
cd telemetry-service
REDIS_ADDR=localhost:6379 \
RABBITMQ_URL=amqp://guest:guest@localhost:15673/ \
go run .
```

Congestion Analysis:

```bash
mvn -f congestion-analysis/pom.xml spring-boot:run
```

Task Assignment:

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15434/task_db \
VESSEL_SERVICE_URL=http://localhost:8081 \
mvn -f task-assignment-service/pom.xml spring-boot:run
```

Notification Service:

```bash
cd notification-service
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
RABBITMQ_URL=amqp://guest:guest@localhost:15673/ \
uvicorn main:app --host 0.0.0.0 --port 8085
```

Drone Simulator:

```bash
cd drone-simulator
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
TELEMETRY_URL=http://localhost:8082/telemetry/ingest python simulate.py
```

## Tests

Python tests can run in this environment without extra infrastructure:

```bash
python3 -m unittest discover -s notification-service/tests -v
python3 -m unittest discover -s drone-simulator/tests -v
```

Go tests require Go 1.22:

```bash
cd telemetry-service && go test ./...
```

Java tests require Maven and Java 17:

```bash
mvn -f vessel-service/pom.xml test
mvn -f congestion-analysis/pom.xml test
mvn -f task-assignment-service/pom.xml test
mvn -f gateway/pom.xml test
```

Static checks used during setup:

```bash
docker compose config --quiet
```

## Build Notes

The Docker workflow builds all services from source:

- Spring services use Maven container images.
- Telemetry Service uses the official Go image and runs `go test ./...` during
  image build.
- Notification Service uses `notification-service/requirements.txt`.
- Drone Simulator is a local helper script and is not part of `docker compose`.

If dependency downloads fail during Docker build, check network access to Maven
Central, the Go module proxy, and PyPI.

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

- Gateway-level demo JWT authentication is implemented. Production use should
  add claim validation, expiry checks, key rotation, and a real identity provider.
- RabbitMQ, databases, and gateway run as single local instances; this is
  acceptable for course/demo scope but not high availability.
- Local secrets are plain environment variables. Production should use a secret
  manager.
- Observability is limited to structured logs and health endpoints; Prometheus,
  Grafana, tracing, or log aggregation can be added later.

## Notification Service

The FastAPI notification service listens to `congestion.alert.notification`,
`task.created`, `task.failed`, `vessel.arrived`, `vessel.docked`,
`vessel.departed`, and `dlq.errors` over RabbitMQ. It writes rotating structured logs to
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
