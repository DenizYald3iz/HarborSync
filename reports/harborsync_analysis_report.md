# HarborSync Mikroservis Analizi ve Multi-Master Kubernetes Tasarimi


Bu rapor, `/home/deniz.yaldiz/practice_k8ns/HarborSync` altindaki kaynak kod, Docker Compose, servis konfigürasyonlari, event sözlesmeleri, testler ve Postman/Newman artifact'lari incelenerek hazirlandi. Kodda dogrulanan bilgiler ayrica belirtildi; kapasite ve Kubernetes önerileri ise mevcut demo yükü üzerine üretim hazirligi varsayimidir.

## 1. Yönetici Özeti

HarborSync, liman sahasindan gelen drone telemetrisini alip konteyner sektörü doluluk/tikaniklik durumunu analiz eden, gerektiginde görev üreten ve olaylari bildirim loglarina aktaran polyglot bir mikroservis sistemidir.

Koddan dogrulanan ana uygulama servis sayisi 6'dır:

| # | Servis | Teknoloji | Port | Rol |
|---|---|---|---|---|
| 1 | API Gateway | Spring Cloud Gateway | 8080 | Giris kapisi, JWT, correlation ID, Redis rate limit |
| 2 | Vessel Service | Spring Boot 3 + PostgreSQL | 8081 | Gemi kaydi/status/berth yönetimi, lifecycle event üretimi |
| 3 | Telemetry Service | Go 1.22 + Redis + RabbitMQ | 8082 | Drone telemetri ingest, Redis son durum, `telemetry.processed` üretimi |
| 4 | Congestion Analysis | Spring Boot 3 + RabbitMQ | 8083 | Telemetry tüketimi, tikaniklik kural motoru, alert üretimi |
| 5 | Task Assignment | Spring Boot 3 + PostgreSQL + RabbitMQ | 8084 | Alert tüketimi, görev üretimi, Vessel Service senkron çagri |
| 6 | Notification Service | FastAPI + RabbitMQ | 8085 | Event tüketimi, rotating structured log |

Altyapi bilesenleri uygulama servis sayisina dahil degildir: RabbitMQ, PostgreSQL-vessel, PostgreSQL-task, Redis. Drone Simulator yardimci trafik üretici script'tir, platform runtime mikroservisi olarak sayilmamalidir.

Mevcut proje Docker Compose odaklidir; Kubernetes/Helm manifesti ve CI/CD pipeline dosyasi yoktur. Multi-master Kubernetes tasarimi için öneri: 3 control-plane + 3 worker node, ingress/LB ile Gateway üzerinden giris, PostgreSQL/RabbitMQ/Redis için HA operator veya tercihen managed servis, uygulama pod'lari stateless deployment olarak en az 2 replica.

## 2. Kaynaklardan Dogrulanan Bulgular

Ana runtime tanimi `docker-compose.yml` içinde yer alir. RabbitMQ, iki ayri PostgreSQL instance'i, Redis ve 6 uygulama servisi burada tanimlidir (`docker-compose.yml:1-171`). Gateway route ve rate-limit ayarlari `gateway/src/main/resources/application.yml:11-37` içinde; JWT secret `gateway/src/main/resources/application.yml:48-49` ile verilir.

Telemetry Service `POST /telemetry/ingest` ve `GET /health` endpointlerini açar (`telemetry-service/main.go:132-135`). Ingest akisi payload'i valide eder, correlation ID üretir veya devralir, fill rate hesaplar, Redis'e drone state yazar ve RabbitMQ'ya event basar (`telemetry-service/main.go:145-183`). Redis yazma hatasi non-fatal ele alinmistir; RabbitMQ publish hatasi HTTP 502 döndürür.

Congestion Analysis fill rate ve blockage kurallarini uygular: blockage + ETA varsa `IMMEDIATE_ACTION`, kritik esik üzeri `SECTOR_CRITICAL`, warning esigi üzeri `SECTOR_WARNING` (`congestion-analysis/.../CongestionRuleEngine.java:28-46`).

Task Assignment alert geldiginde Vessel Service'ten arriving vessel listesini çeker, mümkünse berth reserve eder, task tablosuna yazar ve `task.created` event'i üretir (`task-assignment-service/.../TaskAssignmentService.java:47-92`). Publish hatasinda task'i FAILED yapar ve mümkünse berth release ile kompanzasyon dener (`TaskAssignmentService.java:93-122`).

Notification Service RabbitMQ'dan congestion, task ve vessel lifecycle queue'larini tüketir; consumer QoS prefetch 10 olarak ayarlanmistir (`notification-service/consumer.py:195-245`). Loglar dosya ve console'a yazilir.

Postman/Newman artifact'larina göre 2026-06-11 tarihinde endpoint koleksiyonu 21 request/23 assertion ve simulation koleksiyonu 10 request/10 assertion ile hatasiz kosmustur; ortalama response süreleri sirasiyla 15 ms ve 14 ms olarak kaydedilmistir (`postman/run-artifacts/newman-run-summary.md:21-58`).

## 3. Uygulama Mimarisi

### 3.1 Hiyerarsik Diyagram

```text
HarborSync Platform
|
+-- External Clients
|   +-- REST/Postman/Operator Client
|   +-- Drone Simulator
|
+-- Edge Layer
|   +-- API Gateway (8080)
|       +-- JWT signature validation
|       +-- X-Correlation-ID injection
|       +-- Redis based rate limiting
|       +-- Routes:
|           +-- /api/vessels/** -> Vessel Service
|           +-- /api/tasks/**   -> Task Assignment
|
+-- Domain Services
|   +-- Vessel Service (8081)
|   |   +-- REST CRUD/status/berth
|   |   +-- PostgreSQL vessel_db
|   |   +-- RabbitMQ lifecycle producer
|   |
|   +-- Telemetry Service (8082)
|   |   +-- REST telemetry ingest
|   |   +-- Redis latest drone state, TTL 30s
|   |   +-- RabbitMQ telemetry.processed producer
|   |
|   +-- Congestion Analysis (8083)
|   |   +-- RabbitMQ telemetry.processed consumer
|   |   +-- Rule engine
|   |   +-- RabbitMQ congestion.alert producer
|   |
|   +-- Task Assignment (8084)
|   |   +-- RabbitMQ congestion.alert.task-assignment consumer
|   |   +-- REST /tasks API
|   |   +-- PostgreSQL task_db
|   |   +-- Sync dependency: Vessel Service
|   |   +-- RabbitMQ task.created/task.failed producer
|   |
|   +-- Notification Service (8085)
|       +-- RabbitMQ event consumers
|       +-- Rotating file/console structured logs
|
+-- Shared Infrastructure
    +-- RabbitMQ direct exchange: harborsync.exchange
    +-- PostgreSQL vessel_db
    +-- PostgreSQL task_db
    +-- Redis
```
Şekil 1: HarborSync hiyerarsik uygulama mimarisi.

### 3.2 Event/Dependency Diyagrami

```text
Drone Simulator
    |
    | HTTP POST /telemetry/ingest
    v
Telemetry Service ---- Redis (latest drone state, TTL)
    |
    | RabbitMQ: telemetry.processed
    v
Congestion Analysis
    |
    | RabbitMQ routing key: congestion.alert
    +------------------------------+
    |                              |
    v                              v
Task Assignment                Notification Service
    |                              ^
    | REST GET/PUT                 |
    v                              |
Vessel Service ---- RabbitMQ vessel.* events
    |
    +-- PostgreSQL vessel_db

Task Assignment ---- PostgreSQL task_db
Task Assignment ---- RabbitMQ task.created/task.failed ----> Notification Service

REST Client -> API Gateway -> Vessel Service / Task Assignment
API Gateway -> Redis rate limiter
```
Şekil 2: Servisler arasi event ve dependency akisi.

### 3.3 Senkron ve Asenkron Bagimliliklar

| Kaynak | Hedef | Tip | Etki |
|---|---|---|---|
| REST Client | API Gateway | HTTP | Dis dünyaya açilan ana giris |
| API Gateway | Vessel Service | HTTP | Vessel API proxy |
| API Gateway | Task Assignment | HTTP | Task API proxy |
| API Gateway | Redis | TCP | Rate limit state; Redis kesilirse Gateway davranisi etkilenir |
| Drone Simulator | Telemetry Service | HTTP | Telemetry girisi |
| Telemetry Service | Redis | TCP | Son drone state, non-critical cache |
| Telemetry Service | RabbitMQ | AMQP | Telemetry event üretimi; publish hatasi ingest'i basarisiz yapar |
| Congestion Analysis | RabbitMQ | AMQP | Telemetry tüketimi ve alert üretimi |
| Task Assignment | RabbitMQ | AMQP | Alert tüketimi, task event üretimi |
| Task Assignment | Vessel Service | HTTP | Alert isleme sirasinda senkron liste/reserve/release |
| Vessel Service | PostgreSQL | JDBC | Stateful kalici veri |
| Task Assignment | PostgreSQL | JDBC | Stateful kalici veri |
| Notification Service | RabbitMQ | AMQP | Event tüketimi ve DLQ izleme |

## 4. Servis Sayisi

Eksiksiz ayrim:

| Kategori | Adet | Bilesenler |
|---|---:|---|
| Uygulama mikroservisi | 6 | gateway, vessel-service, telemetry-service, congestion-analysis, task-assignment-service, notification-service |
| Altyapi/runtime bagimliligi | 4 logical | RabbitMQ, Redis, PostgreSQL vessel_db, PostgreSQL task_db |
| Yardimci tool/script | 1 | drone-simulator |
| Test/verification artifact | - | Postman collections, Newman reports, unit tests |

Not: PostgreSQL iki ayri container olarak çalisiyor; domain açisindan "database per service" yaklasimi vardir. Kubernetes tasariminda bunlar ayri PostgreSQL cluster/database olarak ele alinmalidir.

## 5. Stateless / Stateful Ayrimi

| Bilesen | Stateless/Stateful | Gerekçe | Kubernetes sonucu |
|---|---|---|---|
| API Gateway | Stateless uygulama; Redis'e bagimli | Pod içinde kalici veri yok, rate limit Redis'te | Deployment, HPA uygun; Redis HA olmali |
| Vessel Service | Uygulama stateless, veri stateful | Kalici veri PostgreSQL vessel_db'de | Deployment + PostgreSQL HA |
| Telemetry Service | Uygulama stateless, Redis cache state | Son drone state Redis TTL 30s | Deployment + Redis HA; cache kaybi tolere edilebilir |
| Congestion Analysis | Stateless | Sadece event tüketir, kural uygular | Deployment, queue consumer scaling |
| Task Assignment | Uygulama stateless, veri stateful | Kalici task verisi PostgreSQL task_db'de | Deployment + PostgreSQL HA |
| Notification Service | Fiilen stateful log dosyasi var | Rotating file log container filesystem'e yaziliyor | Production'da stdout + log aggregation önerilir; dosya PV istenirse StatefulSet gerekebilir ama önerilmez |
| RabbitMQ | Stateful | Queue, exchange, durable message | RabbitMQ cluster/operator + PV |
| PostgreSQL | Stateful | Kalici relational veri | HA cluster + PV + backup |
| Redis | Duruma göre | Rate limit ve drone state; compose'da persistence kapali | HA Redis/Sentinel veya managed; cache-only kabul edilebilir |

## 6. Trafik Beklentisi ve Yogun Servisler

Koddan dogrulanan demo yükü: Drone Simulator varsayilan olarak 2 saniyede bir telemetry istegi atar, 3 drone ve 4 sektör ile rastgele payload üretir (`drone-simulator/simulate.py:13-35`). Gateway her route için IP basina 10 req/s replenish ve 20 burst limiti uygular (`gateway/src/main/resources/application.yml:20-37`).

Üretim varsayimi için üç trafik profili:

| Profil | Telemetry girisi | REST API | Event etkisi |
|---|---:|---:|---|
| Demo/lab | 0.5 req/s | Düsük | Tek node Docker Compose yeterli |
| Pilot liman | 50-150 telemetry req/s | 10-30 req/s | RabbitMQ ve Congestion yatay ölçek ister |
| Üretim | 300-1000 telemetry req/s | 50-200 req/s | RabbitMQ, Telemetry, Congestion ve Task ana kapasite ekseni olur |

En yogun olmasi beklenen servisler:

1. Telemetry Service: bütün drone verisinin ilk giris noktasi; HTTP ingest + Redis write + RabbitMQ publish yapar.
2. RabbitMQ: event fan-out ve durable queue yükünün merkezi; HA ve disk I/O kritik.
3. Congestion Analysis: telemetry queue tüketim hizini belirler; stateless oldugu için kolay ölçeklenir.
4. Task Assignment: alert basina DB write + RabbitMQ publish + Vessel Service HTTP çagrisi yapar; en riskli orkestrasyon noktasi.
5. PostgreSQL task_db: alert yogunlugunda write-heavy hale gelir.

Olası bottleneckler:

| Bottleneck | Neden | Etki | Öneri |
|---|---|---|---|
| RabbitMQ disk/queue birikimi | Durable message + consumer gecikmesi | Uçtan uca gecikme artar | Queue depth alert, quorum queues, consumer HPA |
| Task Assignment -> Vessel senkron çagri | Alert isleme içinde 10s bloklayan WebClient call | Consumer throughput düser | Timeout/circuit breaker mevcut; bulkhead ve async reserve düsünülebilir |
| PostgreSQL task_db | Her alert task insert yapar | Write latency artar | Index/connection pool tuning, read replica sadece raporlama için |
| Gateway Redis | Rate limiter Redis'e bagimli | API girisi etkilenir | Redis HA, fail-open/fail-closed karari |
| Notification log file | Container diskine log | Pod reschedule'da log kaybi | stdout + Loki/ELK/OpenSearch |
| Consumer'siz `congestion.alert` queue | Congestion Analysis kendi queue'sunu declare/bind ediyor ama consume etmiyor (`RabbitMqConfig.java:39-66`) | RabbitMQ'da gereksiz mesaj birikimi | Bu queue kaldirilmali; sadece routing key ve consumer queue'lari kalmali |

## 7. Database ve Bagimliliklar

### 7.1 PostgreSQL

Vessel Service:

- DB: `vessel_db`
- Tablo: `vessels`
- Önemli alanlar: `id`, `name`, `imo_number`, `status`, `berth`, `eta`, timestamps
- Constraint: IMO unique, status check, status index

Task Assignment:

- DB: `task_db`
- Tablo: `tasks`
- Önemli alanlar: `id`, `sector`, `alert_type`, `assigned_unit`, `priority`, `status`, `correlation_id`, timestamps
- Constraint: status/priority check, status ve sector index

### 7.2 Redis

- Gateway rate limiter backend'i.
- Telemetry latest drone state cache'i: key `drone:<droneId>`, TTL 30s.
- Compose'da Redis persistence kapali: `redis-server --save "" --appendonly no` (`docker-compose.yml:47-52`).

### 7.3 RabbitMQ

- Exchange: `harborsync.exchange`, direct.
- Önemli queue/routing key'ler: `telemetry.processed`, `congestion.alert.task-assignment`, `congestion.alert.notification`, `task.created`, `task.failed`, `vessel.arrived`, `vessel.docked`, `vessel.departed`, `dlq.errors`.
- Business queue'larda DLQ argümanlari kullaniliyor.
- Notification prefetch 10 (`notification-service/consumer.py:195-198`).

### 7.4 Diger Bagimliliklar

- Java 17, Spring Boot 3.2.5, Spring Cloud Gateway 2023.0.1.
- Go 1.22, `amqp091-go`, `go-redis`.
- Python 3.11, FastAPI, uvicorn, aio-pika.

## 8. CI/CD Ihtiyaci

Mevcut repo içinde `.github`, `.gitlab-ci.yml`, Jenkinsfile, Drone CI veya benzeri pipeline bulunmadi. CI/CD ihtiyaci yüksektir; multi-master Kubernetes hedefi için elle build/deploy sürdürülebilir degildir.

Önerilen pipeline:

```text
Pull Request
  -> lint/static check
  -> unit tests:
       Maven test x4
       go test ./...
       python unittest x2
  -> docker build for 6 images
  -> container scan + dependency scan + SBOM
  -> docker compose config validation
  -> optional integration/E2E: compose up + Newman collections
  -> push images to registry
  -> Helm/Kustomize render validation
  -> deploy to dev namespace
  -> smoke tests
  -> manual approval
  -> deploy to stage/prod with rolling/canary
```

Gerekli artifact'lar:

- Her servis için versioned container image.
- Helm chart veya Kustomize overlays: `dev`, `stage`, `prod`.
- Secret yönetimi: External Secrets / Vault / cloud secret manager.
- DB migration job stratejisi: Flyway migration'lari deploy sirasinda kontrollü kosmali.

## 9. HarborSync Icin Multi-Master Kubernetes Tasarimi

Bu tasarim, projenin gerçek servis yapisi ve ölçülen kaynak tüketimi kullanilarak hazirlandi. Tasarim karari su siraya göre verildi: servis envanteri, stateless/stateful ayrimi, replica sayisi, container request/limit degerleri, add-on ihtiyaci, worker kapasitesi, control-plane HA, load balancer HA, dis bagimliliklar, monitoring/logging ve Proxmox toplam kapasitesi.

### 9.1 Tasarim Varsayimlari

| Karar | HarborSync icin secim | Gerekce |
|---|---|---|
| Kubernetes tipi | Multi-master kubeadm cluster | Control-plane HA ve DevOps pratigi hedefi |
| etcd modeli | Stacked etcd | Lab/pilot icin daha sade; master disk latency kritik |
| Load balancer | 2 VM, HAProxy + Keepalived | API server ve Ingress VIP icin HA pratigi |
| Worker sayisi | 3 worker | Pod yayma, rolling update, node failure ve anti-affinity testi |
| Database | Kubernetes disinda DB VM veya managed DB | Stateful karmasikligi azaltir, DB backup/restore ayrisir |
| RabbitMQ/Redis | Lab icin cluster ici olabilir; prod icin managed/operator | Mesajlasma ve rate-limit kritik bagimliliklar |
| Ortam | Once dev, sonra staging, prod simülasyonu en son | Kaynak tüketimini kontrollü büyütmek icin |
| Monitoring/GitOps | Argo CD + Prometheus + Grafana + Loki önerilir | DevOps pratiği ve görünürlük icin |

### 9.2 HarborSync Servislerinin Kubernetes Karsiligi

| Bilesen | Kubernetes nesnesi | Replica | Config/Secret | Dis bagimlilik |
|---|---|---:|---|---|
| API Gateway | Deployment + Service + Ingress | 2 | JWT secret, downstream URL, Redis host | Redis, Vessel, Task |
| Vessel Service | Deployment + Service | 2 | DB/RabbitMQ secret, env config | PostgreSQL vessel_db, RabbitMQ |
| Telemetry Service | Deployment + Service | 3 | Redis/RabbitMQ env | Redis, RabbitMQ |
| Congestion Analysis | Deployment + Service | 2 | RabbitMQ env, threshold ConfigMap | RabbitMQ |
| Task Assignment | Deployment + Service | 2 | DB/RabbitMQ secret, Vessel URL | PostgreSQL task_db, RabbitMQ, Vessel |
| Notification Service | Deployment + Service | 2 | RabbitMQ env, log config | RabbitMQ, logging stack |
| PostgreSQL vessel_db | External DB / managed / VM | - | DB credentials | Backup/PITR |
| PostgreSQL task_db | External DB / managed / VM | - | DB credentials | Backup/PITR |
| RabbitMQ | Operator/StatefulSet veya external | 3 prod | User/pass/TLS secret | PV, backup definitions |
| Redis | Operator/StatefulSet veya external | 3 prod | Auth/TLS secret | Rate limit/cache |

Not: Yeni baslayan Kubernetes pratigi icin PostgreSQL'in cluster disinda tutulmasi dogru yaklasimdir. Böylece önce Deployment, Service, Ingress, HPA, GitOps, monitoring ve rollout pratigi netlesir.

### 9.3 Hedef Multi-Master Topoloji

```text
                          Users / Drone Clients
                                  |
                                  v
                    +-----------------------------+
                    |  Ingress VIP / App VIP      |
                    |  HAProxy + Keepalived       |
                    +-------------+---------------+
                                  |
                       +----------v----------+
                       | Ingress Controller  |
                       +----------+----------+
                                  |
                         +--------v--------+
                         | API Gateway SVC |
                         +--------+--------+
                                  |
        +-------------------------+-------------------------+
        |                                                   |
+-------v--------+                                  +-------v--------+
| Vessel Service |                                  | Task Assignment|
+-------+--------+                                  +-------+--------+
        |                                                   |
        | JDBC                                              | JDBC
        v                                                   v
+------------------+                              +------------------+
| external         |                              | external         |
| PostgreSQL       |                              | PostgreSQL       |
| vessel_db        |                              | task_db          |
+------------------+                              +------------------+

Telemetry direct ingest -> Telemetry Service -> RabbitMQ -> Congestion -> Task/Notification
                                 |              ^              |
                                 v              |              v
                              Redis        RabbitMQ HA     Log stack
```
Şekil 3: HarborSync hedef multi-master Kubernetes topolojisi.

### 9.4 Cluster Node Diyagrami

```text
                 Kubernetes API VIP
                         |
        +----------------+----------------+
        |                |                |
+-------v------+ +-------v------+ +-------v------+
| master-1     | | master-2     | | master-3     |
| api-server   | | api-server   | | api-server   |
| scheduler    | | scheduler    | | scheduler    |
| controller   | | controller   | | controller   |
| stacked etcd | | stacked etcd | | stacked etcd |
+-------+------+ +-------+------+ +-------+------+
        |                |                |
        +----------------+----------------+
                         |
                 Kubernetes Cluster
                         |
        +----------------+----------------+
        |                |                |
+-------v------+ +-------v------+ +-------v------+
| worker-1     | | worker-2     | | worker-3     |
| app pods     | | app pods     | | app pods     |
| ingress      | | workers      | | monitoring   |
| cni/coredns  | | cni/coredns  | | cni/coredns  |
+--------------+ +--------------+ +--------------+

+-----------+     +-----------+
| lb-1      |<--->| lb-2      |
| HAProxy   | VIP | HAProxy   |
| Keepalived|     | Keepalived|
+-----------+     +-----------+
```
Şekil 4: Multi-master cluster node ve load balancer yerlesimi.

### 9.5 Uygulama Kaynak Hesabi

13.3 bölümündeki ölçümlerden ve HA replica kararlarindan hareketle uygulama request toplami asagidaki gibidir. Bu hesap sadece HarborSync uygulama pod'laridir; Kubernetes add-on'lari, monitoring/logging, image cache ve bos kapasite ayrica eklenmelidir.

| Servis | Replica | CPU request | RAM request | Toplam CPU | Toplam RAM |
|---|---:|---:|---:|---:|---:|
| API Gateway | 2 | 250m | 512Mi | 500m | 1Gi |
| Vessel Service | 2 | 300m | 768Mi | 600m | 1.5Gi |
| Telemetry Service | 3 | 100m | 128Mi | 300m | 384Mi |
| Congestion Analysis | 2 | 250m | 512Mi | 500m | 1Gi |
| Task Assignment | 2 | 500m | 1Gi | 1000m | 2Gi |
| Notification Service | 2 | 100m | 128Mi | 200m | 256Mi |
| **Toplam uygulama** | **13 pod** | - | - | **3.1 CPU** | **~6.1Gi RAM** |

Platform payi eklenmis worker ihtiyaci:

| Kalem | CPU | RAM | Not |
|---|---:|---:|---|
| HarborSync uygulama request | ~3.1 CPU | ~6.1Gi | 13 pod |
| Ingress + CNI + CoreDNS + metrics-server + cert-manager + MetalLB | ~1-2 CPU | ~2-4Gi | Minimal platform |
| Argo CD | ~0.5-1 CPU | ~1-2Gi | GitOps |
| Prometheus + Grafana + Loki | ~2-4 CPU | ~6-12Gi | Retention'a göre büyür |
| Bosluk payi | +30-50% | +30-50% | Rolling update ve node failure icin |
| **Önerilen worker havuzu** | **12 CPU+** | **36-48Gi+** | 3 worker x 4 CPU / 12-16Gi |

Sonuç: HarborSync uygulamasi tek basina küçük/orta boydur; worker boyutunu büyüten ana kalem monitoring/logging ve DevOps add-on'laridir. Bu nedenle 3 worker x 4 vCPU / 12-16 GB RAM mantikli baslangictir.

### 9.6 Önerilen VM Kaynaklari

| VM tipi | Adet | vCPU | RAM | Disk | Not |
|---|---:|---:|---:|---:|---|
| Load Balancer | 2 | 1 | 1-2 GB | 20 GB | HAProxy + Keepalived |
| Master | 3 | 2 | 6-8 GB | 60-80 GB SSD | Stacked etcd; disk latency önemli |
| Worker | 3 | 4 | 12-16 GB | 100-150 GB SSD | App + add-on + monitoring |
| External DB | 1 | 2-4 | 4-8 GB | 100-200 GB SSD | Lab icin tek VM; prod icin HA/managed |
| CI Runner | 1 | 2 | 4 GB | 50-100 GB | Build/test/deploy job'lari |
| Monitoring/Logging VM | Opsiyonel | 2-4 | 8-16 GB | 100-300 GB | Cluster disi gözlemleme istenirse |

Minimum Proxmox host kapasitesi:

| Seviye | CPU | RAM | Disk |
|---|---|---|---|
| Minimum lab | 8 core / 16 thread | 64 GB | 1 TB SSD/NVMe |
| Rahat lab/pilot | 12-16 core / 24-32 thread | 128 GB | 2 TB+ SSD/NVMe |
| Gercek fiziksel HA | 3 fiziksel Proxmox node | 128 GB+ toplam/node profiline göre | Replicated/ZFS/Ceph tasarimi |

Tek Proxmox host üzerinde 3 master + 3 worker kurmak Kubernetes seviyesinde HA pratigidir; fiziksel HA degildir. Host kapanirsa tüm cluster gider. Gercek HA için en az 3 fiziksel Proxmox node gerekir.

### 9.7 Ortam ve Namespace Plani

Baslangiçta tüm ortamlari ayni anda açmak yerine kademeli ilerlemek daha sagliklidir.

| Faz | Namespace | Replica yaklasimi | Amaç |
|---|---|---:|---|
| Faz 1 | `harborsync-dev` | Kritik servisler 1, Gateway 1 | Manifest, ingress, secret, config denemesi |
| Faz 2 | `harborsync-staging` | Gateway/Vessel/Task 2, Telemetry 2 | Rolling update, HPA, smoke test |
| Faz 3 | `harborsync-prod-sim` | Rapordaki replica hedefleri | HA, node failure, backup/restore tatbikati |

Prod benzeri senaryoda namespace'ler:

```text
harborsync-dev
harborsync-staging
harborsync-prod
observability
ingress-nginx
cert-manager
argocd
external-secrets
rabbitmq-system     # RabbitMQ operator kullanilirsa
redis-system        # Redis operator kullanilirsa
```

### 9.8 Add-on Plani

| Add-on | Gerekli mi | Kaynak etkisi | Not |
|---|---|---|---|
| CNI | Evet | Düsük/orta | Cilium veya Calico |
| CoreDNS | Evet | Düsük | Control plane icin temel |
| Ingress Controller | Evet | Orta | NGINX Ingress veya Traefik |
| MetalLB | Bare-metal/Proxmox icin evet | Düsük | LoadBalancer servis tipi icin |
| cert-manager | Önerilir | Düsük | TLS otomasyonu |
| metrics-server | Evet | Düsük | HPA icin gerekli |
| Argo CD | Önerilir | Orta | GitOps deploy modeli |
| Prometheus/Grafana | Önerilir | Orta/yüksek | Metrik ve dashboard |
| Loki/Promtail | Önerilir | Orta/yüksek disk | Log retention'a göre büyür |
| KEDA | Önerilir | Düsük | RabbitMQ queue depth bazli scale |
| External Secrets | Önerilir | Düsük | Vault/cloud secret entegrasyonu |

### 9.9 Ingress, Service ve Network Akisi

```text
Internet / Lab Client
        |
        v
App VIP (HAProxy/Keepalived veya MetalLB)
        |
        v
Ingress Controller
        |
        +--> /api/vessels/**  -> api-gateway -> vessel-service
        +--> /api/tasks/**    -> api-gateway -> task-assignment-service
        +--> /telemetry/*     -> telemetry-service veya gateway route karari

Cluster internal:
  task-assignment-service -> vessel-service:8081
  services -> rabbitmq:5672
  gateway/telemetry -> redis:6379
  vessel/task -> external PostgreSQL endpoint
```
Şekil 5: Ingress, service ve cluster içi network akisi.

Güvenlik karari: Dis dünyaya sadece Ingress/Gateway açilmali. Vessel, Task, Congestion, Notification, RabbitMQ ve Redis ClusterIP veya internal endpoint olarak kalmali. Telemetry ingest dogrudan dis açilacaksa ayri rate-limit/auth karari gerekir.

### 9.10 Scheduling, Anti-Affinity ve PDB

| Bilesen | Scheduling önerisi |
|---|---|
| Gateway | 2 replica farkli worker node'lara yayilmali |
| Telemetry | 3 replica mümkünse 3 worker'a dagilmali |
| Task Assignment | Vessel Service ile ayni node'a zorunlu baglanmamali; network üzerinden konusmali |
| RabbitMQ | Cluster icinde calisacaksa 3 node, podAntiAffinity ve ayrik PV |
| Monitoring | Worker kaynaklarini tüketmemesi icin request/limit net olmali |
| PDB | Gateway, Vessel, Task, Telemetry icin minimum available tanimlanmali |

### 9.11 HarborSync Icin Karar Özeti

| Karar basligi | Nihai öneri |
|---|---|
| Master | 3 adet, 2 vCPU / 6-8 GB RAM / 60-80 GB SSD |
| Worker | 3 adet, 4 vCPU / 12-16 GB RAM / 100-150 GB SSD |
| LB | 2 adet, 1 vCPU / 1-2 GB RAM / 20 GB disk |
| DB | Kubernetes disinda 1 DB VM ile basla; prod icin HA/managed PostgreSQL |
| RabbitMQ | Lab icin cluster ici mümkün; prod icin 3 node operator/managed |
| Redis | Lab icin tek/HA Redis; prod icin Sentinel/managed |
| Replica | Gateway 2, Vessel 2, Telemetry 3, Congestion 2, Task 2, Notification 2 |
| Kaynak toplamı | Uygulama request ~3.1 CPU / ~6.1Gi RAM; add-on ve boslukla 36-48Gi worker RAM hedeflenmeli |
| Ortam | Dev ile basla, staging ekle, prod simülasyonu en son |
| CI/CD | Argo CD + image registry + test/build/scan pipeline |
| Monitoring/logging | Prometheus, Grafana, Loki; RabbitMQ queue depth ve JVM/DB metrikleri kritik |
| Backup | External PostgreSQL PITR, RabbitMQ definitions/PV snapshot, Velero manifests |

## 10. HA Ihtiyaci

HA ihtiyaci yüksektir çünkü sistem operasyonel karar üretir ve event zincirindeki kopukluk task/notification gecikmesine neden olur.

| Bilesen | HA seviyesi | Öneri |
|---|---|---|
| Kubernetes API | Kritik | 3 control-plane, stacked veya external etcd; API LB |
| Gateway | Yüksek | En az 2 replica, PodDisruptionBudget |
| Telemetry | Yüksek | En az 3 replica; rate ve publish latency izlenmeli |
| Congestion | Yüksek | En az 2 replica; queue depth ile autoscale |
| Task Assignment | Kritik | En az 2 replica; idempotency iyilestirilmeli |
| Vessel | Orta-yüksek | En az 2 replica |
| Notification | Orta | En az 2 replica; ayni queue'da competing consumer semantigi dikkate alinmali |
| RabbitMQ | Kritik | 3 node cluster, quorum queues |
| PostgreSQL | Kritik | Primary + 2 replica, automatic failover |
| Redis | Orta-yüksek | 3 node Sentinel/cluster; rate limiter için HA |

## 11. Backup/Restore Ihtiyaci

| Veri | Kritik mi | Backup önerisi | RPO/RTO hedefi |
|---|---|---|---|
| vessel_db | Evet | Günlük full + WAL archiving/PITR | RPO <= 5 dk, RTO <= 30 dk |
| task_db | Evet | Günlük full + WAL archiving/PITR | RPO <= 5 dk, RTO <= 30 dk |
| RabbitMQ durable queues | Evet, kisa süreli | Definitions export + persistent volumes snapshot; quorum queue | RPO <= 5-15 dk |
| Redis rate/cache | Düsük/orta | Rate/cache ise backup gerekmez; kritik state eklenirse AOF/RDB | RPO best-effort |
| Notification logs | Audit gerekiyorsa evet | Merkezi log sistemi retention + object storage | Retention 30-180 gün |
| Kubernetes manifests/secrets | Evet | GitOps repo + sealed/external secrets | Git restore |

Restore testleri ayda en az bir kez stage ortaminda denenmeli. PostgreSQL için `pgBackRest`, `Barman` veya operator native backup; RabbitMQ için definitions + PV snapshot; Kubernetes için Velero önerilir.

## 12. Güvenlik Ihtiyaci

Mevcut güvenlik durumu demo seviyesindedir:

- Gateway JWT filtresi sadece HS256 imzasini dogrular; exp/nbf/aud/iss/scope kontrolü yoktur (`JwtAuthFilter.java:54-65`).
- Default secret ve credential degerleri `.env.example` ve compose içinde görünür.
- Direct service portlari compose'da host'a açiktir.
- TLS/mTLS, network policy, pod security, image signing ve secret manager yoktur.

Üretim önerileri:

| Alan | Öneri |
|---|---|
| Kimlik dogrulama | OIDC provider, JWT claim validation, token expiry, JWKS/key rotation |
| Network | Sadece Ingress -> Gateway dis açik; servisler ClusterIP; NetworkPolicy ile namespace içi kisit |
| TLS | Ingress TLS, iç trafik için mTLS/service mesh opsiyonel |
| Secrets | Kubernetes Secret yerine External Secrets + Vault/cloud secret manager |
| RabbitMQ/PostgreSQL/Redis | Strong credentials, TLS, least privilege user, network isolation |
| Container | Non-root user, read-only root FS, seccomp, drop capabilities |
| Supply chain | Image scan, SBOM, cosign signing, admission policy |
| Observability | Prometheus/Grafana, centralized logging, tracing, alerting |
| API hardening | Request size limit, schema validation, WAF/rate policy, actuator exposure kisitlama |

## 13. CPU/RAM Ihtiyaci

Kodda Kubernetes resource request/limit tanimi yoktur; asagidaki degerler pilot/üretime geçis için baslangiç önerisidir. JVM servislerinde gerçek heap ölçümü sonrasi ayar revize edilmelidir.

### 13.1 Uygulama Pod Baslangiç Kaynaklari

| Servis | Request CPU | Request RAM | Limit CPU | Limit RAM | Not |
|---|---:|---:|---:|---:|---|
| gateway | 250m | 512Mi | 1000m | 1Gi | Reactive gateway + Redis rate limit |
| vessel-service | 300m | 768Mi | 1500m | 1.5Gi | Spring + JPA |
| telemetry-service | 200m | 256Mi | 1000m | 512Mi | Go servis, network/Rabbit publish yoğun |
| congestion-analysis | 250m | 512Mi | 1000m | 1Gi | Stateless rule engine |
| task-assignment-service | 500m | 1Gi | 2000m | 2Gi | JPA + Rabbit consumer + WebClient |
| notification-service | 150m | 256Mi | 500m | 512Mi | FastAPI/aio-pika |

### 13.2 Stateful Altyapi Baslangiç Kaynaklari

| Bilesen | Replica | Request CPU | Request RAM | Storage |
|---|---:|---:|---:|---:|
| RabbitMQ | 3 | 1000m | 2Gi | 50-100Gi SSD/PV per node |
| PostgreSQL vessel | 3 | 1000m | 4Gi | 100Gi+ SSD/PV |
| PostgreSQL task | 3 | 1000m | 4Gi | 100Gi+ SSD/PV |
| Redis | 3 | 500m | 1Gi | 10-20Gi opsiyonel |

### 13.3 Çalistirma Bazli Kaynak Ölçümü ve Net Request/Limit Tablosu

Bu bölüm 2026-06-25 tarihinde proje Docker Compose ile çalistirilarak hazirlandi. Host üzerinde `keycloak` container'i 127.0.0.1:8080 portunu kullandigi için Compose Gateway 8080'e bind edemedi; mevcut servise dokunmadan ayni Gateway imaji `api-gateway-measure` adiyla `harborsync_default` network'ünde `18080:8080` portuyla çalistirildi.

Ölçüm yöntemi:

- Tüm servis health endpointleri kontrol edildi: Gateway, Vessel, Telemetry, Congestion, Task Assignment ve Notification `UP` döndü.
- Boşta `docker stats --no-stream` ile kaynak tüketimi alindi.
- Kisa örnek yük uygulandi: Gateway üzerinden Vessel API'ye 80 GET, Telemetry Service'e 160 POST gönderildi. Bu trafik Congestion -> Task Assignment -> Notification event zincirini tetikledi.
- Yük sonrasi `task_db` içinde 92 adet `PENDING` task olustugu görüldü.
- RabbitMQ tarafinda `telemetry.processed`, `congestion.alert.task-assignment`, `congestion.alert.notification`, `task.created` kuyruklari bosaldi. Buna karsilik consumer'i olmayan `congestion.alert` kuyruğunda 92 mesaj birikti; bu, önceki bottleneck notunu çalistirma ile de dogruladi.

Gözlenen runtime degerleri:

| Bilesen | Boşta RAM | Yükte tepe CPU | Yükte tepe RAM | Siniflandirma |
|---|---:|---:|---:|---|
| API Gateway | 243Mi | 17.20% | 259Mi | Basit/orta API gateway |
| Vessel Service | 287Mi | 23.94% | 297Mi | Basit API + JPA |
| Telemetry Service | 5Mi | 2.83% | 7Mi | Hafif ingest API/producer |
| Congestion Analysis | 195Mi | 9.85% | 197Mi | Worker/consumer |
| Task Assignment | 415Mi | 63.52% | 484Mi | Yogun worker/API + JPA |
| Notification Service | 41Mi | 2.18% | 41Mi | Hafif worker/consumer |
| RabbitMQ | 151Mi | 81.32% | 152Mi | Mesaj broker |
| Redis | 5Mi | 2.96% | 5Mi | Küçük Redis/cache |
| PostgreSQL vessel_db | 65Mi | 5.57% | 66Mi | Küçük lab DB |
| PostgreSQL task_db | 52Mi | 1.16% | 52Mi | Küçük lab DB |

Net Kubernetes request/limit önerisi:

| Servis tipi / bilesen | CPU request | RAM request | CPU limit | RAM limit |
|---|---:|---:|---:|---:|
| API Gateway | 250m | 512Mi | 1000m | 1Gi |
| Vessel Service | 300m | 768Mi | 1500m | 1536Mi |
| Telemetry Service | 100m | 128Mi | 500m | 512Mi |
| Congestion Analysis | 250m | 512Mi | 1000m | 1Gi |
| Task Assignment Service | 500m | 1Gi | 2000m | 2Gi |
| Notification Service | 100m | 128Mi | 500m | 512Mi |
| RabbitMQ küçük lab | 500m | 512Mi | 2000m | 2Gi |
| RabbitMQ production baseline | 1000m | 2Gi | 2000m | 4Gi |
| Redis küçük lab | 100m | 128Mi | 500m | 512Mi |
| Redis production baseline | 250m | 512Mi | 500m | 1Gi |
| PostgreSQL vessel_db küçük lab | 500m | 512Mi | 2000m | 2Gi |
| PostgreSQL task_db küçük lab | 500m | 512Mi | 2000m | 2Gi |
| PostgreSQL production baseline | 1000m | 2Gi | 2000m | 4Gi |

Yorum:

- Docker CPU yüzdesi kisa burst ölçümüdür; uzun süreli kapasite testi yerine baslangiç request/limit belirlemek için kullanildi.
- Spring/JVM servislerinde heap ve native memory payi nedeniyle observed RAM'in en az 2x'i request/limit kararinda korunmalidir. Bu yüzden 195-484Mi arasi gözlenen JVM pod'larina 512Mi-1Gi request önerildi.
- Task Assignment ölçümde en yüksek uygulama CPU'sunu gördü. Bunun sebebi alert tüketimi sirasinda hem PostgreSQL yazmasi hem Vessel Service HTTP çagrisi hem RabbitMQ publish yapmasidir.
- RabbitMQ kisa burst'te en yüksek CPU spike'i yapan altyapi bileseni oldu. Production'da queue depth ve disk I/O metrikleri ile HPA/KEDA degil, broker cluster kapasitesi ve quorum queue tasarimi belirleyici olacaktir.
- Telemetry Service Go ile yazildigi için RAM footprint çok düsük; buna ragmen network/RabbitMQ publish burst'leri için 100m/128Mi altina inilmemelidir.
- PostgreSQL lab ölçümünde RAM düsük görünür; gerçek üretim için buffer/cache, connection sayisi, WAL ve index büyüklügü nedeniyle 2Gi altina inilmemelidir.

### 13.4 Cluster Kapasite Önerisi

Minimum pilot: 3 worker x 8 vCPU/32 GB RAM. Stateful workload cluster içinde çalisacaksa bu kaynak ancak baslangiçtir; production için 3-5 worker x 16 vCPU/64 GB RAM daha rahat olur. Managed DB/Rabbit/Redis kullanilirsa 3 worker x 8 vCPU/32 GB RAM uygulama tarafi için yeterli baslangiçtir.

## 14. Replica Ihtiyaci

| Servis | Min replica | Autoscale önerisi | Gerekçe |
|---|---:|---|---|
| gateway | 2 | CPU/RPS/p95 latency | Edge HA |
| vessel-service | 2 | CPU/latency | REST ve Task dependency HA |
| telemetry-service | 3 | CPU/RPS/Rabbit publish latency | En yogun ingest noktasi |
| congestion-analysis | 2 | RabbitMQ `telemetry.processed` queue depth | Stateless consumer |
| task-assignment-service | 2 | RabbitMQ `congestion.alert.task-assignment` depth + CPU | DB write ve Vessel dependency |
| notification-service | 2 | Queue depth/CPU | Bildirim gecikmesini azaltma |
| RabbitMQ | 3 | N/A | Quorum/cluster HA |
| PostgreSQL cluster'lari | 3 | N/A | Primary + replicas/failover |
| Redis | 3 | N/A | Sentinel/cluster HA |

KEDA, RabbitMQ queue depth'e göre Congestion ve Task consumer'larini ölçeklemek için uygundur. HPA yalniz CPU ile kullanilirsa event backlog geç fark edilebilir.

## 15. Operasyonel Gözlemler ve Iyilestirme Listesi

1. `congestion.alert` consumer'siz queue birikimi düzeltilmeli. Congestion Analysis producer routing key kullanmali; kendi adina consume edilmeyen durable queue declare etmemeli.
2. Task Assignment idempotency güçlendirilmeli. Ayni alert tekrar teslim edilirse duplicate task üretimi mümkün; correlationId + sector + alertType için idempotency key düsünülebilir.
3. RabbitMQ publisher confirm kullanimi eklenmeli. Su an publish çagrilari uygulama seviyesinde basari kabul ediyor; broker confirm/return handling üretim için kritik.
4. Notification log dosyasi container local filesystem yerine stdout + merkezi logging olmalı.
5. JWT claim validation ve secret rotasyonu eklenmeli.
6. Kubernetes manifests/Helm chart, resource request/limit, readiness/liveness probe, PDB ve NetworkPolicy yazilmali.
7. DB migration deployment süreci netlesmeli: Flyway otomatik kosuyor, fakat multi-replica deployment sirasinda migration lock ve rollout sirasi kontrol edilmeli.
8. Observability eklenmeli: RabbitMQ queue depth, consumer lag, DB latency, HTTP p95, error rate, JVM heap, Go runtime metrics.

## 16. Sonuç

HarborSync mikroservis sinirlari net, event-driven akisi anlasilir ve Docker Compose ile dogrulanmis bir demo platformudur. Eksiksiz uygulama servis sayisi 6'dir. Üretim veya ciddi pilot hedefi için esas is, uygulama kodundan çok operasyonel katmandadir: HA RabbitMQ/PostgreSQL/Redis, Kubernetes deployment standartlari, CI/CD, secret yönetimi, observability, backup/restore ve güvenlik sertlestirmesi.

Multi-master Kubernetes için önerilen baslangiç: 3 control-plane + 3 worker, uygulama pod'lari Deployment olarak en az 2 replica, Telemetry 3 replica, RabbitMQ/PostgreSQL/Redis HA olarak operator/managed servislerle kurulmalidir. Sistemin en kritik performans ekseni Telemetry -> RabbitMQ -> Congestion -> Task Assignment zinciridir; kapasite testleri öncelikle bu zincir üzerinde yapilmalidir.

## 17. Proxmox Ihtiyaci - Demo Ortami

Bu bölüm demo ve pratik ortamı içindir. Sisteme gerçek üretim trafiği veya büyük veri girmeyecek; maksimum test verisi, kısa süreli load testleri ve Kubernetes/DevOps pratiği yapılacaktır. Bu nedenle Proxmox tarafında aşırı kaynak istemeye gerek yoktur. Gerçek production bir sistem olsaydı 9. bölümdeki daha geniş kaynak yaklaşımı, HA DB, daha büyük worker kaynakları, ayrı monitoring kapasitesi ve fiziksel HA dikkate alınmalıydı.

### 17.1 Demo Varsayimi

| Baslik | Demo karari |
|---|---|
| Trafik | Düşük/orta test trafiği, kısa süreli burst |
| Veri | Az miktarda test verisi |
| Database | Kubernetes dışında tek PostgreSQL VM yeterli |
| RabbitMQ/Redis | Demo için cluster içinde küçük kaynakla çalışabilir veya tek VM/container olarak tutulabilir |
| Monitoring | Hafif Prometheus/Grafana/Loki; uzun retention yok |
| CI/CD | Küçük bir runner VM yeterli |
| Fiziksel HA | Tek Proxmox host üzerinde Kubernetes HA pratiği; gerçek fiziksel HA değil |

### 17.2 Istenen Demo VM Kaynaklari

| VM | Adet | vCPU | RAM | Disk | Açiklama |
|---|---:|---:|---:|---:|---|
| Load Balancer | 2 | 1 | 1 GB | 20 GB | HAProxy + Keepalived için yeterli |
| Master | 3 | 2 | 4-6 GB | 50-60 GB SSD | Stacked etcd demo için yeterli; mümkünse SSD |
| Worker | 3 | 4 | 8-12 GB | 80-100 GB SSD | Uygulama pod'ları + temel add-on'lar |
| External DB | 1 | 2 | 4 GB | 80-100 GB SSD | Az test verisi için PostgreSQL yeterli |
| CI Runner | 1 | 2 | 4 GB | 50 GB | Build/test/deploy işleri için |
| Monitoring VM | Opsiyonel | 2 | 4-8 GB | 80-100 GB | Monitoring cluster dışında tutulacaksa |

### 17.3 Minimum Demo Toplami

Monitoring ayrı VM olarak açılmaz, monitoring/logging cluster içinde hafif tutulursa yaklaşık ihtiyaç:

| Kaynak | Yaklaşik toplam |
|---|---:|
| vCPU | 25 vCPU |
| RAM | 50-65 GB |
| VM disk | 650-800 GB |

Bu toplam, VM'lere ayrılan teorik kaynaktır. Proxmox host tarafında hypervisor, disk snapshot, image cache ve büyüme payı için ek boşluk bırakılmalıdır.

### 17.4 Rahat Demo Toplami

Monitoring VM de açılacaksa veya worker'lara 12 GB RAM verilecekse daha rahat demo ihtiyacı:

| Kaynak | Yaklaşik toplam |
|---|---:|
| vCPU | 27-30 vCPU |
| RAM | 70-85 GB |
| VM disk | 800 GB - 1 TB |

### 17.5 Proxmox Fiziksel Host Önerisi

| Seviye | CPU | RAM | Disk | Uygunluk |
|---|---|---|---|---|
| Minimum demo | 8 core / 16 thread | 64 GB | 1 TB SSD/NVMe | Çalışır; monitoring/logging hafif tutulmalı |
| Rahat demo | 12 core / 24 thread | 96 GB | 1.5 TB SSD/NVMe | Daha sorunsuz demo ve snapshot alanı |
| Çok rahat demo | 12-16 core / 24-32 thread | 128 GB | 2 TB SSD/NVMe | Daha fazla ortam ve monitoring retention için |

Demo için en makul istek: 8 core / 16 thread CPU, 64 GB RAM ve 1 TB SSD/NVMe ile başlanabilir. Eğer elde imkan varsa 96 GB RAM ve 1.5 TB disk daha rahat olur. 128 GB RAM ve 2 TB disk production benzeri lab için konfor sağlar ama bu demo için zorunlu değildir.

### 17.6 Demo Icin Kaynaklari Küçük Tutma Kararlari

| Alan | Demo karari | Gerçek sistemde ne olurdu? |
|---|---|---|
| PostgreSQL | Tek external DB VM | HA PostgreSQL, replica, PITR, ayrı backup storage |
| RabbitMQ | Küçük cluster içi kurulum veya tek node lab | 3 node quorum queue cluster veya managed RabbitMQ |
| Redis | Tek küçük Redis veya hafif HA | Sentinel/managed Redis |
| Monitoring retention | Kısa süreli, düşük disk | Uzun retention, ayrı disk ve kapasite planı |
| Worker RAM | 8-12 GB | 16 GB+ ve node sayısı artırımı |
| Disk | 80-100 GB worker disk | Image cache, log, volume ve retention'a göre 150-300 GB+ |
| Fiziksel HA | Tek Proxmox host kabul | En az 3 fiziksel host, shared/replicated storage |

### 17.7 Net Talep Metni

Demo ortamı için Proxmox tarafında istenecek kaynak özeti:

```text
2 adet Load Balancer VM:  1 vCPU, 1 GB RAM, 20 GB disk
3 adet Master VM:         2 vCPU, 4-6 GB RAM, 50-60 GB SSD disk
3 adet Worker VM:         4 vCPU, 8-12 GB RAM, 80-100 GB SSD disk
1 adet External DB VM:    2 vCPU, 4 GB RAM, 80-100 GB SSD disk
1 adet CI Runner VM:      2 vCPU, 4 GB RAM, 50 GB disk
Opsiyonel Monitoring VM:  2 vCPU, 4-8 GB RAM, 80-100 GB disk
```

Bu kaynaklar HarborSync demosu, Kubernetes multi-master pratiği, rolling update, temel monitoring, GitOps ve kısa süreli test yükleri için yeterlidir. Gerçek production hedeflenseydi daha yüksek worker kaynakları, HA database, daha büyük disk, uzun log/metric retention ve fiziksel Proxmox HA tasarımı gerekir.

## 18. Uygulama Fazi - Güncel Mentor Kararina Göre Yol Haritasi

Bu bölüm raporun önceki analizinden sonra alınan güncel uygulama kararlarını temel alır. Önceki bölümlerde dış DB ve ayrı LB VM seçenekleri değerlendirilmişti; mentor kararıyla demo/pratik ortamı için tasarım artık şu şekilde uygulanacaktır:

| Konu | Güncel karar |
|---|---|
| Master node | 3 adet master/control-plane |
| Worker node | 3 adet worker |
| Load balancer | Ayrı LB VM yok; HAProxy + Keepalived master node'lara kurulacak |
| Database | PostgreSQL Kubernetes içine kurulacak |
| RabbitMQ | Kubernetes içine kurulacak |
| Redis | Kubernetes içine kurulacak |
| Monitoring | Helm ile Kubernetes içine kurulacak |
| CI runner | Şimdilik iptal; manuel build/deploy veya lokal pipeline ile ilerlenebilir |
| Amaç | Demo, öğrenme, mikroservis deploy pratiği, HA davranışı gözlemleme |

Bu kararla cluster daha öğretici hale gelir; çünkü sadece uygulama pod'larını değil, stateful workload, storage, Helm release, monitoring ve node failure etkilerini de göreceğiz. Bedeli şudur: Storage, backup, StatefulSet/Operator ve kaynak yönetimi artık daha dikkatli yapılmalıdır.

### 18.1 Bu Projede Liderlik Prensibi

Bu uygulamayı yaparken amacımız komut ezberlemek değil, her kararın neden verildiğini anlayarak ilerlemek. Her adımda üç soruya cevap arayacağız:

1. Bu bileşen cluster içinde hangi problemi çözüyor?
2. Bu bileşen bozulursa hangi servis etkilenir?
3. Kurulumdan sonra çalıştığını hangi somut sinyalle doğrularız?

Örnek: PostgreSQL'i kurmak sadece `helm install` çalıştırmak değildir. Önce şunu bilmeliyiz: Vessel Service ve Task Assignment kalıcı veri tutar; bu yüzden PostgreSQL pod'u silinse bile veri PV üzerinde kalmalıdır. Doğrulama da sadece pod `Running` görmek değildir; database'e bağlanıp schema migration'ın oluştuğunu ve servislerin health verdiğini görmektir.

### 18.2 Güncel Hedef Mimari

```text
                         Client / Operator / Drone
                                  |
                                  v
                  +-----------------------------------+
                  | VIP                               |
                  | Keepalived on master nodes        |
                  +----------------+------------------+
                                   |
                                   v
                  +-----------------------------------+
                  | HAProxy on master nodes           |
                  | Kubernetes API + Ingress traffic  |
                  +----------------+------------------+
                                   |
                         +---------v---------+
                         | Ingress Controller|
                         +---------+---------+
                                   |
                         +---------v---------+
                         | API Gateway       |
                         +----+---------+----+
                              |         |
                              v         v
                    Vessel Service   Task Assignment
                              |         |
                              v         v
                    PostgreSQL in Kubernetes

Telemetry Service -> Redis in Kubernetes
Telemetry Service -> RabbitMQ in Kubernetes -> Congestion -> Task/Notification
Monitoring Helm chart -> Prometheus/Grafana/Loki inside Kubernetes
```
Şekil 6: Güncel mentor kararına göre HarborSync Kubernetes hedef mimarisi.

### 18.3 Node Rolleri

| Node | Rol | Üzerinde olacak ana işler |
|---|---|---|
| master-1 | Control-plane + HAProxy/Keepalived | API server, scheduler, controller-manager, etcd, LB süreci |
| master-2 | Control-plane + HAProxy/Keepalived | API server, scheduler, controller-manager, etcd, LB süreci |
| master-3 | Control-plane + HAProxy/Keepalived | API server, scheduler, controller-manager, etcd, LB süreci |
| worker-1 | Worker | Uygulama pod'ları, ingress, monitoring parçaları |
| worker-2 | Worker | Uygulama pod'ları, RabbitMQ/Redis/PostgreSQL pod'ları |
| worker-3 | Worker | Uygulama pod'ları, monitoring/logging, stateful replica dağılımı |

Master node'lara LB kurulacak olması şu anlama gelir: Ayrı LB VM yönetmeyeceğiz, fakat master node'ların işletim sistemi seviyesinde HAProxy/Keepalived süreçleri doğru yönetilmelidir. Bu süreçler Kubernetes pod'u gibi değil, node üzerindeki systemd servisleri gibi düşünülmelidir.

### 18.4 Uygulama Sirası

Bu sırayı bozmayacağız; çünkü alttaki katman doğrulanmadan üst katmana çıkmak hata ayıklamayı zorlaştırır.

| Sıra | Faz | Amaç | Bittiğini nasıl anlarız? |
|---:|---|---|---|
| 1 | VM ve OS hazırlığı | 3 master + 3 worker hazır olmalı | Hostname, static IP, DNS/hosts, SSH, disk hazır |
| 2 | HAProxy + Keepalived | Kubernetes API VIP hazır olmalı | VIP hangi master aktifse orada görülür, API portu cevap verir |
| 3 | Kubernetes bootstrap | Multi-master control-plane kurulmalı | `kubectl get nodes` 6 node'u gösterir |
| 4 | CNI kurulumu | Pod network çalışmalı | CoreDNS Running, pod'lar node'lar arası konuşur |
| 5 | StorageClass | Stateful workload için PV üretimi | Test PVC Bound olur |
| 6 | Ingress controller | HTTP giriş katmanı hazır olmalı | Ingress controller pod'ları Running, test route cevap verir |
| 7 | cert-manager/MetalLB gerekiyorsa | TLS ve LoadBalancer davranışı | Certificate/LoadBalancer kaynakları hazır |
| 8 | PostgreSQL | Vessel ve Task DB hazır | DB pod Running, PVC Bound, connection test başarılı |
| 9 | RabbitMQ | Event broker hazır | Management/AMQP erişimi ve queue declare başarılı |
| 10 | Redis | Gateway rate-limit ve telemetry cache hazır | Redis ping başarılı |
| 11 | Monitoring Helm | Metrik/log görünürlüğü | Grafana/Prometheus açılır, node/pod metrikleri gelir |
| 12 | HarborSync manifests | Uygulamalar deploy edilir | Tüm health endpointleri UP |
| 13 | Demo flow | Uçtan uca senaryo çalışır | Telemetry -> alert -> task -> notification akışı görülür |
| 14 | Failure test | HA davranışı anlaşılır | Pod silme/node drain sonrası servis toparlanır |

### 18.5 Kubernetes Içindeki Stateful Bilesenler

Bu kararda PostgreSQL, RabbitMQ ve Redis Kubernetes içinde olacağı için önce storage mantığını netleştirmeliyiz.

| Bileşen | Kubernetes tipi | Demo yaklaşımı | Dikkat edilecek nokta |
|---|---|---|---|
| PostgreSQL | StatefulSet veya Helm chart | Tek primary ile başlanabilir | PVC silinirse veri gider; migration sırası önemli |
| RabbitMQ | Helm chart/operator | Demo için tek node veya 3 replica | Queue durability ve disk alanı izlenmeli |
| Redis | Helm chart | Tek node veya sentinel opsiyonel | Gateway rate-limit Redis'e bağlı |
| Monitoring | Helm chart | kube-prometheus-stack + opsiyonel Loki | Disk retention küçük tutulmalı |

Demo olduğu için her stateful bileşeni en baştan production HA ile kurmak zorunda değiliz. Ama şunu bilerek ilerleyeceğiz: PostgreSQL ve RabbitMQ cluster içindeyse worker diskleri ve StorageClass artık uygulamanın güvenilirliğinin parçasıdır.

### 18.6 Namespace Plani

Başlangıçta fazla namespace açmayacağız. Önce sistemi çalıştırıp sonra ayıracağız.

| Namespace | İçerik |
|---|---|
| `harborsync` | Uygulama servisleri: gateway, vessel, telemetry, congestion, task, notification |
| `data` | PostgreSQL, Redis, RabbitMQ |
| `monitoring` | Prometheus, Grafana, Loki/Promtail |
| `ingress-nginx` | Ingress controller |
| `cert-manager` | TLS otomasyonu gerekiyorsa |

Bu ayrım hata ayıklamada yardımcı olur. Örneğin uygulama çalışmıyorsa `harborsync`; database sorunu varsa `data`; metrik/log sorunu varsa `monitoring` namespace'ine bakarız.

### 18.7 Manifest ve Helm Yaklaşımı

HarborSync servisleri için kendi manifestlerimizi yazacağız. PostgreSQL, RabbitMQ, Redis ve monitoring için Helm chart kullanmak daha mantıklı; çünkü bu bileşenlerin StatefulSet, Service, Secret, PVC ve probe ayarları elle yazıldığında hata riski artar.

| Bileşen | Yaklaşım |
|---|---|
| HarborSync uygulama servisleri | Kendi Deployment/Service/ConfigMap/Secret manifestleri |
| PostgreSQL | Helm chart veya operator |
| RabbitMQ | Helm chart veya operator |
| Redis | Helm chart |
| Monitoring | Helm chart, tercihen kube-prometheus-stack |
| Loki | Helm chart, retention düşük |
| Ingress | Helm chart |

Burada seni ezber komutlara boğmayacağız. Her Helm release için önce values dosyasını okuyacağız, sonra sadece gerekli ayarları override edeceğiz. Mantık şu: Chart'ın varsayılanını anlamadan values yazmak kör deploy yapmaktır.

### 18.8 HarborSync Deploy Sirası

Uygulama servislerini de sırayla kuracağız. Çünkü bağımlılık zinciri var.

| Sıra | Servis | Ön koşul | Doğrulama |
|---:|---|---|---|
| 1 | PostgreSQL vessel_db/task_db | StorageClass hazır | DB connection ve migration |
| 2 | RabbitMQ | data namespace hazır | Exchange/queue oluşumu |
| 3 | Redis | data namespace hazır | `PING/PONG` |
| 4 | Vessel Service | vessel_db + RabbitMQ | `/actuator/health` UP |
| 5 | Task Assignment | task_db + RabbitMQ + Vessel URL | `/actuator/health` UP |
| 6 | Telemetry Service | Redis + RabbitMQ | `/health` UP |
| 7 | Congestion Analysis | RabbitMQ | `/actuator/health` UP |
| 8 | Notification Service | RabbitMQ | `/health` UP ve RabbitMQ connected |
| 9 | API Gateway | Redis + Vessel + Task | `/actuator/health` UP ve route testi |
| 10 | Ingress | Gateway service | Dışarıdan API erişimi |

Özellikle API Gateway'i en sona bırakıyoruz. Çünkü Gateway dış kapıdır; arkadaki servisler sağlıklı değilken Gateway test etmek yanıltıcı olur.

### 18.9 Ilk Basit Basari Kriteri

Ilk hedefimiz şudur:

```text
kubectl get nodes
  -> 3 master + 3 worker Ready

kubectl get pods -A
  -> system pod'ları Running
  -> data namespace pod'ları Running
  -> harborsync namespace pod'ları Running

Demo akışı:
  1. Vessel oluştur
  2. Telemetry gönder
  3. Congestion alert üretildiğini gör
  4. Task oluştuğunu gör
  5. Notification log/metrik çıktısını gör
```

Şekil 7: Uygulama fazı için ilk başarı kriteri.

Bu noktaya gelmeden HPA, TLS, advanced monitoring, backup gibi konulara geçmeyeceğiz. Önce çalışan sade sistemi kuracağız; sonra olgunlaştıracağız.

### 18.10 Senden Beklediğim Çalışma Şekli

Bu projede senden beklenen şey komut kopyalamak değil, her çıktıdan anlam çıkarmak olacak. Beraber ilerlerken her adımda şu bilgileri not edeceğiz:

| Bakılacak şey | Neden önemli? |
|---|---|
| Pod hangi node'a schedule oldu? | Anti-affinity ve kaynak dağılımını anlamak için |
| Pod neden restart etti? | Probe, config, secret veya dependency hatasını ayırmak için |
| Service endpoint var mı? | Label selector doğru mu anlamak için |
| PVC Bound mu? | Stateful workload gerçekten storage aldı mı görmek için |
| ConfigMap/Secret doğru mu? | Uygulama environment hatalarını azaltmak için |
| Loglarda ilk hata ne? | Belirti ile kök sebebi ayırmak için |
| Health endpoint ne diyor? | Uygulama gerçekten hazır mı anlamak için |

Bir hata çıktığında ilk refleksimiz tekrar deploy etmek olmayacak. Önce şu sırayla bakacağız:

```text
1. pod status
2. pod events
3. container logs
4. env/config/secret
5. service endpoints
6. dependency health
7. resource pressure
```

Bu alışkanlık seni ezberden çıkarıp gerçek DevOps problem çözme seviyesine taşır.

### 18.11 Bu Fazda Ertelediklerimiz

Şimdilik bazı konuları bilinçli erteliyoruz. Bu zayıflık değil, doğru sıralama.

| Ertelenen konu | Neden şimdi değil? |
|---|---|
| CI runner | Mentor kararıyla şimdilik iptal; önce cluster ve deploy öğrenilecek |
| Full production DB HA | Demo veri az; önce StatefulSet/PVC mantığı öğrenilecek |
| Uzun log retention | Disk tüketimini büyütür; demo için kısa retention yeterli |
| Service mesh | Önce temel network/service/ingress öğrenilmeli |
| Advanced autoscaling | Önce sabit replica ile stabil sistem kurulmalı |
| Gerçek fiziksel HA | Tek Proxmox üzerinde Kubernetes HA pratiği yapılacak |

### 18.12 Benim Sana Lead Edeceğim Uygulama Sirasi

Bundan sonraki pratik çalışma sırası şu olacak:

1. VM planını kesinleştiririz: IP, hostname, CPU/RAM/disk.
2. Master node'larda HAProxy/Keepalived tasarımını netleştiririz.
3. Kubernetes cluster bootstrap sırasını belirleriz.
4. CNI ve StorageClass seçimini yaparız.
5. Helm ile ingress, data katmanı ve monitoring'i kurarız.
6. HarborSync için image, manifest, secret ve config dosyalarını hazırlarız.
7. Uygulamaları sırayla deploy ederiz.
8. Demo akışını çalıştırırız.
9. Pod silme, node drain, service failover testleri yaparız.
10. En son rapordaki tasarım ile gerçek gözlemleri karşılaştırırız.

Bu sırada her adımda senden sadece komut çalıştırmanı değil, çıktıyı yorumlamanı isteyeceğim. Örneğin `kubectl get pods` çıktısını gördüğümüzde soru şu olacak: "Hangi pod hazır değil, neden hazır değil, hangi dependency eksik olabilir?" Bu şekilde ilerlersek proje sonunda sadece çalışan bir cluster değil, gerçekten anladığın bir Kubernetes pratiği çıkmış olur.

