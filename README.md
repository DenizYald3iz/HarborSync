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

## Kubernetes Deployment (Bare-Metal HA)

Besides Docker Compose, HarborSync ships a full Kubernetes deployment used to run
the platform on a self-hosted **3 control-plane + 3 worker** cluster fronted by a
Keepalived VIP and HAProxy. All manifests are under [`k8s/`](k8s/) and the
cluster-level bootstrap material under [`infra/`](infra/).

> **Placeholders:** Every IP, hostname, VRRP password and network interface in
> `infra/` is a sanitized example. Replace them with your own values before use:
> IPs use `192.168.10.0/24` (`.150` = API VIP, `.151` = ingress VIP), the VRRP
> secret is `CHANGE_ME_VRRP_PASS`, and the NIC is `ens18`.

### Layout

```
infra/
├── haproxy/haproxy.cfg          # LB for the API (:8443) and ingress (:80/:443)
├── keepalived/master{1,2,3}.conf# VIPs: API + ingress
├── kubeadm/kubeadm-init.example.yaml
└── calico/                      # CNI (Calico v3.32.0, Tigera Operator)
k8s/
├── platform/gateway/            # Gateway API + Envoy Gateway (Calico-managed)
└── apps/harborsync/             # Namespace, secrets, infra + microservices
```

### Prerequisites

- 6 Linux nodes (3 control-plane, 3 worker) with `containerd`, `kubeadm`,
  `kubelet`, `kubectl` (v1.36.x) installed and swap handled per your config.
- Two external load balancers (or one HA pair) running **HAProxy + Keepalived**
  from [`infra/`](infra/) — see [`docs/kubernetes-ha-gateway.md`](docs/kubernetes-ha-gateway.md).
- Service images available to the cluster (see step 4). The manifests reference
  `harborsync/<service>:local` with `imagePullPolicy: IfNotPresent`.

### 1. Load balancer + VIPs

Deploy `infra/haproxy/haproxy.cfg` and the matching `infra/keepalived/masterN.conf`
onto your LB nodes (adjust IPs/NIC/VRRP password first). This gives you:

- API VIP `:8443` → control-plane `:6443` (used as `controlPlaneEndpoint`)
- Ingress VIP `:80/:443` → worker NodePorts `30080/30443`

### 2. Bootstrap the control plane

```bash
# On the first control-plane node (adjust IPs/hostnames in the file first):
sudo kubeadm init --config infra/kubeadm/kubeadm-init.example.yaml --upload-certs
```

Join the other two control-plane nodes and the three workers using the
`kubeadm join ...` commands printed by `init`. Configure `kubectl`:

```bash
mkdir -p ~/.kube && sudo cp /etc/kubernetes/admin.conf ~/.kube/config
sudo chown "$(id -u):$(id -g)" ~/.kube/config
```

### 3. Install the CNI (Calico)

```bash
kubectl create -f https://raw.githubusercontent.com/projectcalico/calico/v3.32.0/manifests/tigera-operator.yaml
kubectl create -f infra/calico/installation.yaml
kubectl create -f infra/calico/apiserver.yaml
kubectl get tigerastatus        # wait until everything is Available
kubectl get nodes -o wide       # all nodes should become Ready
```

The pod CIDR in `infra/calico/installation.yaml` (`172.30.0.0/16`) **must match**
`networking.podSubnet` in the kubeadm config. See [`infra/calico/README.md`](infra/calico/README.md).

### 4. Make service images available

The manifests use locally-tagged images. Build them from source and make them
resolvable on every worker, either by pushing to a registry (and updating the
`image:` fields) or by importing them into each node's `containerd`:

```bash
# Example: build and import one service on each worker
docker build -t harborsync/vessel-service:local ./vessel-service
docker save harborsync/vessel-service:local | \
  sudo ctr -n k8s.io images import -
```

Repeat for `gateway`, `telemetry-service`, `congestion-analysis`,
`task-assignment-service`, and `notification-service`.

### 5. Label the ingress nodes

The Envoy gateway pods are pinned to nodes labelled for ingress (and spread with
anti-affinity). Label at least as many workers as gateway replicas (3):

```bash
kubectl label nodes <worker1> <worker2> <worker3> harborsync.io/ingress-gateway=true
```

### 6. Gateway API + Envoy Gateway

Gateway API is provided by the Calico operator (no upstream CRD install needed).
Apply the platform manifests in order:

```bash
kubectl apply -f k8s/platform/gateway/00-gateway-api-enable.yaml   # GatewayAPI operator CR
kubectl apply -f k8s/platform/gateway/10-envoy-proxy.yaml          # namespace + EnvoyProxy (NodePort 30080/30443)
kubectl apply -f k8s/platform/gateway/20-public-gateway.yaml       # Gateway (needs TLS secret from step 7)
# 30/40-demo-web* are optional connectivity checks
```

### 7. Create the TLS secret

The `https` listener terminates TLS with `app-harborsync-lab-tls` in the
`harborsync-gateway` namespace. Generate a self-signed cert (see
[`docs/kubernetes-ha-gateway.md`](docs/kubernetes-ha-gateway.md) for a CA-signed flow):

```bash
openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
  -keyout tls.key -out tls.crt -subj "/CN=app.harborsync.lab" \
  -addext "subjectAltName=DNS:app.harborsync.lab"

kubectl create secret tls app-harborsync-lab-tls \
  --cert=tls.crt --key=tls.key -n harborsync-gateway
```

### 8. Deploy the application

```bash
kubectl apply -f k8s/apps/harborsync/
```

This creates the `harborsync` namespace, the `harborsync-runtime` secret,
ephemeral PostgreSQL/RabbitMQ/Redis, all six services, and the public
`HTTPRoute`. The demo credentials in `00-namespace-secrets.yaml`
(`harbor/harbor123`, `harborsync/harborsync123`, demo JWT secret) are for lab use
— replace them for anything beyond the course scope.

### 9. Access and verify

Point `app.harborsync.lab` at the ingress VIP, then call the API:

```bash
echo "192.168.10.151 app.harborsync.lab" | sudo tee -a /etc/hosts

curl -k https://app.harborsync.lab/actuator/health
curl -k https://app.harborsync.lab/api/tasks/pending -H "Authorization: Bearer <demo-jwt>"

kubectl -n harborsync get pods
kubectl -n harborsync-gateway get pods,svc
```

Routing (see [`k8s/apps/harborsync/30-public-route.yaml`](k8s/apps/harborsync/30-public-route.yaml)):
`/api` and `/actuator` → API Gateway (`:8080`), `/telemetry` → Telemetry Service (`:8082`).

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
