# HarborSync — Geliştirici El Kitabı
### Distributed Port Cargo Coordination Platform
**CENG-442 Microservice Architecture | Versiyon 1.0**
**Takım:** Emirhan Ersoy & Deniz Yaldız

---

## İçindekiler

1. [Projeye Genel Bakış](#1-projeye-genel-bakış)
2. [Takım Görev Dağılımı](#2-takım-görev-dağılımı)
3. [Ortak Altyapı ve Geliştirme Ortamı](#3-ortak-altyapı-ve-geliştirme-ortamı)
4. [EMİRHAN — Vessel Service](#4-emirhan--vessel-service)
5. [EMİRHAN — Congestion Analysis Service](#5-emirhan--congestion-analysis-service)
6. [EMİRHAN — API Gateway](#6-emirhan--api-gateway)
7. [DENİZ — Telemetry Service](#7-deniz--telemetry-service)
8. [DENİZ — Task Assignment Service](#8-deniz--task-assignment-service)
9. [DENİZ — Notification Service](#9-deniz--notification-service)
10. [Servislerarası İletişim Sözleşmeleri](#10-servislerarası-i̇letişim-sözleşmeleri)
11. [Test Stratejisi](#11-test-stratejisi)
12. [Geliştirme Takvimi](#12-geliştirme-takvimi)

---

## 1. Projeye Genel Bakış

HarborSync, liman sahasını izleyen drone simülatörlerinden gelen telemetri verilerini tüketen, konteyner sektörlerindeki tıkanıklıkları analiz eden ve vinç/saha ekiplerine otomatik görev atayan bir dağıtık koordinasyon platformudur.

### 1.1 Mimari Özet

```
[ DRONE SİMÜLATÖRÜ ]  [ REST İSTEMCİSİ (Postman) ]
        |                          |
        | RabbitMQ (telemetry.raw) | HTTPS
        ↓                          ↓
┌─────────────────────────────────────────────────┐
│            API GATEWAY (Spring Cloud)            │
│   CorrelationID Injection | Rate Limit | Auth    │
└──────────────┬──────────────────────────────────┘
               |
   ┌───────────┴────────────────────────────┐
   ↓           ↓           ↓               ↓
Vessel     Telemetry   Congestion      Task
Service    Service     Analysis        Assignment
(Spring)   (Spring)    (Spring)        (Spring)
   |           |           |               |
PostgreSQL   Redis      stateless       PostgreSQL
                            |
                      Notification
                        Service
                        (FastAPI)
                            |
                         log file

[ MESSAGE BROKER: RabbitMQ | DLQ | Docker Compose ]
```

### 1.2 Sistem Tasarım Kararları (Özet)

| Karar | Seçim | Gerekçe |
|---|---|---|
| Mesajlaşma | RabbitMQ | Kafka'ya göre düşük operasyonel karmaşıklık, DLQ desteği |
| Servis izolasyonu | Database per Service | Servis sınırlarını güçlendirir, SPOF önler |
| Tutarlılık modeli | Eventual Consistency | Dağıtık sistemde distributed transaction gereksiz |
| Telemetry işleme | Stateless | Yatay ölçekleme kolaylığı, state kaybı riski yok |
| Frontend | Yok | Sadece REST API + structured log çıktısı |

---

## 2. Takım Görev Dağılımı

> Bu dağılım rastgele belirlenmiştir. Her servis tamamen ilgili kişinin sorumluluğundadır.
> Ortak altyapı (Docker Compose, RabbitMQ konfigürasyonu) birlikte geliştirilir.

### Emirhan

| Servis | Teknoloji | Veritabanı | İletişim Tipi |
|---|---|---|---|
| **Vessel Service** | Spring Boot 3 | PostgreSQL 15 | REST (producer) |
| **Congestion Analysis Service** | Spring Boot 3 | Yok (stateless) | RabbitMQ consumer/producer |
| **API Gateway** | Spring Cloud Gateway | — | HTTP/Reactive |

### Deniz

| Servis | Teknoloji | Veritabanı | İletişim Tipi |
|---|---|---|---|
| **Telemetry Service** | Go 1.22 | Redis 7 | RabbitMQ producer |
| **Task Assignment Service** | Spring Boot 3 | PostgreSQL 15 | RabbitMQ consumer + REST |
| **Notification Service** | FastAPI (Python 3.11) | Yok (log dosyası) | RabbitMQ consumer |

### Paylaşılan Sorumluluklar

| Bileşen | Sorumlu |
|---|---|
| `docker-compose.yml` | İkisi birlikte |
| RabbitMQ exchange/queue tanımları | İkisi birlikte |
| Ortak DTO/event şema sözleşmeleri | İkisi birlikte |
| Demo akışı testi | İkisi birlikte |

---

## 3. Ortak Altyapı ve Geliştirme Ortamı

### 3.1 Proje Dizin Yapısı

```
harborsync/
├── docker-compose.yml
├── .env
├── README.md
├── gateway/                    ← Emirhan
├── vessel-service/             ← Emirhan
├── congestion-analysis/        ← Emirhan
├── telemetry-service/          ← Deniz
├── task-assignment-service/    ← Deniz
├── notification-service/       ← Deniz
└── drone-simulator/            ← Deniz (basit script)
```

### 3.2 Tek Bir Spring Boot Servisinin İç Yapısı

Her Spring Boot servisi aynı paket yapısını takip etmelidir:

```
com.harborsync.<servisadi>/
├── controller/          # REST endpoint'leri (varsa)
├── service/             # İş mantığı
├── repository/          # JPA/Redis repository'leri
├── domain/              # Entity sınıfları
├── dto/                 # Request/Response DTO'ları
├── messaging/
│   ├── consumer/        # RabbitMQ listener'ları
│   └── producer/        # RabbitMQ publisher'ları
├── config/              # RabbitMQ, Redis, vb. konfigürasyonlar
└── exception/           # Custom exception sınıfları
```

### 3.3 `docker-compose.yml` (Tam Yapı)

```yaml
version: '3.8'

services:
  rabbitmq:
    image: rabbitmq:3.13-management
    container_name: harborsync-rabbitmq
    ports:
      - "5672:5672"       # AMQP
      - "15672:15672"     # Management UI → http://localhost:15672
    environment:
      RABBITMQ_DEFAULT_USER: guest
      RABBITMQ_DEFAULT_PASS: guest
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5

  postgres-vessel:
    image: postgres:15
    container_name: vessel-db
    environment:
      POSTGRES_DB: vessel_db
      POSTGRES_USER: harbor
      POSTGRES_PASSWORD: harbor123
    ports:
      - "5433:5432"

  postgres-task:
    image: postgres:15
    container_name: task-db
    environment:
      POSTGRES_DB: task_db
      POSTGRES_USER: harbor
      POSTGRES_PASSWORD: harbor123
    ports:
      - "5434:5432"

  redis:
    image: redis:7-alpine
    container_name: harborsync-redis
    ports:
      - "6379:6379"
    command: redis-server --save "" --appendonly no

  vessel-service:
    build: ./vessel-service
    container_name: vessel-service
    ports:
      - "8081:8081"
    depends_on:
      postgres-vessel:
        condition: service_started
      rabbitmq:
        condition: service_healthy
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://vessel-db:5432/vessel_db
      SPRING_RABBITMQ_HOST: rabbitmq

  telemetry-service:
    build: ./telemetry-service
    container_name: telemetry-service
    ports:
      - "8082:8082"
    depends_on:
      - redis
      - rabbitmq
    environment:
      SPRING_REDIS_HOST: redis
      SPRING_RABBITMQ_HOST: rabbitmq

  congestion-analysis:
    build: ./congestion-analysis
    container_name: congestion-analysis
    ports:
      - "8083:8083"
    depends_on:
      rabbitmq:
        condition: service_healthy

  task-assignment-service:
    build: ./task-assignment-service
    container_name: task-assignment-service
    ports:
      - "8084:8084"
    depends_on:
      - postgres-task
      - rabbitmq
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://task-db:5432/task_db
      VESSEL_SERVICE_URL: http://vessel-service:8081

  notification-service:
    build: ./notification-service
    container_name: notification-service
    ports:
      - "8085:8085"
    depends_on:
      - rabbitmq

  gateway:
    build: ./gateway
    container_name: api-gateway
    ports:
      - "8080:8080"
    depends_on:
      - vessel-service
      - task-assignment-service
    environment:
      VESSEL_SERVICE_URL: http://vessel-service:8081
      TASK_SERVICE_URL: http://task-assignment-service:8084
```

### 3.4 RabbitMQ Exchange ve Queue Yapısı

```
Exchanges:
  harborsync.exchange  (type: direct)

Queues:
  telemetry.raw           → binding key: telemetry.raw
  telemetry.processed     → binding key: telemetry.processed
  congestion.alert        → binding key: congestion.alert
  task.created            → binding key: task.created
  dlq.errors              → Dead Letter Queue (tüm kuyruklar için)
```

Her kuyruğun DLQ'ya yönlendirilmesi için `x-dead-letter-exchange` argümanı set edilmelidir.

---

## 4. EMİRHAN — Vessel Service

### 4.1 Sorumluluk

Liman sahasına gelen ve ayrılan gemilerin kaydı, durum geçişleri ve REST API yönetimi.
Bu servis aynı zamanda **Task Assignment Service'in doğrudan bağlandığı** tek REST kaynağıdır.

### 4.2 Teknoloji Yığını

| Katman | Teknoloji | Versiyon |
|---|---|---|
| Framework | Spring Boot | 3.2.x |
| ORM | Spring Data JPA + Hibernate | — |
| Veritabanı | PostgreSQL | 15 |
| Migration | Flyway | — |
| Build | Maven | 3.9.x |
| Port | 8081 | — |

### 4.3 Maven `pom.xml` Bağımlılıkları

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>
</dependencies>
```

### 4.4 `application.yml`

```yaml
server:
  port: 8081

spring:
  application:
    name: vessel-service
  datasource:
    url: jdbc:postgresql://localhost:5433/vessel_db
    username: harbor
    password: harbor123
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: validate          # Flyway yönetir, Hibernate dokunmaz
    show-sql: false
  flyway:
    enabled: true
    locations: classpath:db/migration

logging:
  pattern:
    console: "[%d{HH:mm:ss}] [%-5level] [%X{correlationId}] %logger{36} - %msg%n"
```

### 4.5 Veritabanı Şeması (Flyway Migration)

Dosya: `src/main/resources/db/migration/V1__create_vessels_table.sql`

```sql
CREATE TABLE vessels (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(100) NOT NULL,
    imo_number  VARCHAR(20) UNIQUE NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'ARRIVING',
    berth       VARCHAR(10),
    eta         TIMESTAMP,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Status geçiş kontrolü için CHECK constraint
ALTER TABLE vessels ADD CONSTRAINT chk_vessel_status
    CHECK (status IN ('ARRIVING', 'DOCKED', 'DEPARTING', 'DEPARTED'));

CREATE INDEX idx_vessel_status ON vessels(status);
```

### 4.6 Domain Entity

```java
// domain/Vessel.java
@Entity
@Table(name = "vessels")
@Data
@NoArgsConstructor
public class Vessel {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "imo_number", unique = true, nullable = false)
    private String imoNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VesselStatus status = VesselStatus.ARRIVING;

    private String berth;
    private LocalDateTime eta;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
```

```java
// domain/VesselStatus.java
public enum VesselStatus {
    ARRIVING, DOCKED, DEPARTING, DEPARTED
}
```

### 4.7 DTO Sınıfları

```java
// dto/CreateVesselRequest.java
@Data
public class CreateVesselRequest {
    @NotBlank
    private String name;
    @NotBlank
    private String imoNumber;
    private LocalDateTime eta;
}

// dto/UpdateVesselStatusRequest.java
@Data
public class UpdateVesselStatusRequest {
    @NotNull
    private VesselStatus status;
    private String berth;
}

// dto/VesselResponse.java
@Data
@Builder
public class VesselResponse {
    private UUID id;
    private String name;
    private String imoNumber;
    private VesselStatus status;
    private String berth;
    private LocalDateTime eta;
}
```

### 4.8 Repository

```java
// repository/VesselRepository.java
public interface VesselRepository extends JpaRepository<Vessel, UUID> {
    List<Vessel> findByStatus(VesselStatus status);
    Optional<Vessel> findByImoNumber(String imoNumber);
}
```

### 4.9 Service Katmanı

```java
// service/VesselService.java
@Service
@RequiredArgsConstructor
@Slf4j
public class VesselService {

    private final VesselRepository vesselRepository;

    public VesselResponse registerVessel(CreateVesselRequest request) {
        Vessel vessel = new Vessel();
        vessel.setName(request.getName());
        vessel.setImoNumber(request.getImoNumber());
        vessel.setEta(request.getEta());
        vessel.setStatus(VesselStatus.ARRIVING);

        Vessel saved = vesselRepository.save(vessel);
        log.info("Vessel registered: {} ({})", saved.getName(), saved.getId());
        return toResponse(saved);
    }

    public List<VesselResponse> getVesselsByStatus(VesselStatus status) {
        return vesselRepository.findByStatus(status)
            .stream()
            .map(this::toResponse)
            .toList();
    }

    public VesselResponse updateStatus(UUID id, UpdateVesselStatusRequest request) {
        Vessel vessel = vesselRepository.findById(id)
            .orElseThrow(() -> new VesselNotFoundException("Vessel not found: " + id));
        vessel.setStatus(request.getStatus());
        if (request.getBerth() != null) vessel.setBerth(request.getBerth());
        return toResponse(vesselRepository.save(vessel));
    }

    private VesselResponse toResponse(Vessel v) {
        return VesselResponse.builder()
            .id(v.getId())
            .name(v.getName())
            .imoNumber(v.getImoNumber())
            .status(v.getStatus())
            .berth(v.getBerth())
            .eta(v.getEta())
            .build();
    }
}
```

### 4.10 Controller

```java
// controller/VesselController.java
@RestController
@RequestMapping("/vessels")
@RequiredArgsConstructor
public class VesselController {

    private final VesselService vesselService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VesselResponse registerVessel(@Valid @RequestBody CreateVesselRequest request) {
        return vesselService.registerVessel(request);
    }

    @GetMapping
    public List<VesselResponse> listVessels(
            @RequestParam(required = false) VesselStatus status) {
        if (status != null) return vesselService.getVesselsByStatus(status);
        return vesselService.getAllVessels();
    }

    @GetMapping("/{id}")
    public VesselResponse getVessel(@PathVariable UUID id) {
        return vesselService.getVesselById(id);
    }

    @PutMapping("/{id}/status")
    public VesselResponse updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateVesselStatusRequest request) {
        return vesselService.updateStatus(id, request);
    }
}
```

### 4.11 Exception Handling

```java
// exception/VesselNotFoundException.java
public class VesselNotFoundException extends RuntimeException {
    public VesselNotFoundException(String message) { super(message); }
}

// exception/GlobalExceptionHandler.java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(VesselNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleNotFound(VesselNotFoundException ex) {
        return Map.of("error", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleValidation(MethodArgumentNotValidException ex) {
        return ex.getBindingResult().getFieldErrors().stream()
            .collect(Collectors.toMap(FieldError::getField, FieldError::getDefaultMessage));
    }
}
```

### 4.12 Correlation ID MDC Filtresi

Bu filtre her servise eklenmelidir. Gateway'den gelen `X-Correlation-ID` header'ını MDC'ye yazar.

```java
// config/CorrelationIdFilter.java
@Component
@Order(1)
public class CorrelationIdFilter implements Filter {

    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) req;
        String correlationId = httpReq.getHeader(CORRELATION_HEADER);
        if (correlationId == null) correlationId = UUID.randomUUID().toString();
        MDC.put("correlationId", correlationId);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.clear();
        }
    }
}
```

---

## 5. EMİRHAN — Congestion Analysis Service

### 5.1 Sorumluluk

`telemetry.processed` kuyruğunu dinler, önceden tanımlı iş kurallarını değerlendirir ve eşik aşılırsa `congestion.alert` kuyruğuna alarm üretir. **Veritabanı yoktur; tamamen stateless çalışır.**

### 5.2 Teknoloji Yığını

| Katman | Teknoloji | Versiyon |
|---|---|---|
| Framework | Spring Boot 3 | 3.2.x |
| Mesajlaşma | Spring AMQP (RabbitMQ) | — |
| Build | Maven | 3.9.x |
| Port | 8083 | — |

### 5.3 Maven Bağımlılıkları

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-amqp</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
        <!-- Sadece actuator/health endpoint için -->
    </dependency>
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
    </dependency>
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>
</dependencies>
```

### 5.4 `application.yml`

```yaml
server:
  port: 8083

spring:
  application:
    name: congestion-analysis
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest

harborsync:
  queues:
    telemetry-processed: telemetry.processed
    congestion-alert: congestion.alert
  thresholds:
    critical: 0.90
    warning: 0.85
```

### 5.5 RabbitMQ Konfigürasyonu

```java
// config/RabbitMQConfig.java
@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE = "harborsync.exchange";
    public static final String TELEMETRY_PROCESSED_QUEUE = "telemetry.processed";
    public static final String CONGESTION_ALERT_QUEUE = "congestion.alert";
    public static final String DLQ = "dlq.errors";

    @Bean
    public DirectExchange harborSyncExchange() {
        return new DirectExchange(EXCHANGE);
    }

    @Bean
    public Queue telemetryProcessedQueue() {
        return QueueBuilder.durable(TELEMETRY_PROCESSED_QUEUE)
            .withArgument("x-dead-letter-exchange", "")
            .withArgument("x-dead-letter-routing-key", DLQ)
            .build();
    }

    @Bean
    public Queue congestionAlertQueue() {
        return QueueBuilder.durable(CONGESTION_ALERT_QUEUE)
            .withArgument("x-dead-letter-exchange", "")
            .withArgument("x-dead-letter-routing-key", DLQ)
            .build();
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DLQ).build();
    }

    @Bean
    public Binding telemetryBinding(Queue telemetryProcessedQueue, DirectExchange exchange) {
        return BindingBuilder.bind(telemetryProcessedQueue)
            .to(exchange).with(TELEMETRY_PROCESSED_QUEUE);
    }

    @Bean
    public Binding congestionBinding(Queue congestionAlertQueue, DirectExchange exchange) {
        return BindingBuilder.bind(congestionAlertQueue)
            .to(exchange).with(CONGESTION_ALERT_QUEUE);
    }

    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter());
        return template;
    }
}
```

### 5.6 Event DTO'ları

```java
// dto/ProcessedTelemetryEvent.java
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedTelemetryEvent {
    private String correlationId;
    private String sector;
    private double fillRate;
    private boolean blockageDetected;
    private String droneId;
    private String vesselEta;     // null ise yaklaşan gemi yok
    private String timestamp;
}

// dto/CongestionAlertEvent.java
@Data
@Builder
public class CongestionAlertEvent {
    private String correlationId;
    private String alertType;     // SECTOR_CRITICAL | SECTOR_WARNING | IMMEDIATE_ACTION
    private String sector;
    private String severity;      // HIGH | MEDIUM | LOW
    private double fillRate;
    private String recommendedAction;
    private String timestamp;
}
```

### 5.7 Kural Motoru (Core Logic)

```java
// service/CongestionRuleEngine.java
@Service
@Slf4j
public class CongestionRuleEngine {

    @Value("${harborsync.thresholds.critical}")
    private double criticalThreshold;

    @Value("${harborsync.thresholds.warning}")
    private double warningThreshold;

    /**
     * Telemetri event'ine karşılık gelen alarm tipini hesaplar.
     * Kural motoru tamamen stateless; tüm kararlar anlık veriye dayanır.
     */
    public Optional<CongestionAlertEvent> evaluate(ProcessedTelemetryEvent event) {

        // Kural 1: Tıkanıklık + yaklaşan gemi → IMMEDIATE_ACTION (en yüksek öncelik)
        if (event.isBlockageDetected() && event.getVesselEta() != null) {
            return Optional.of(buildAlert(event, "IMMEDIATE_ACTION", "HIGH", "HOLD_VESSEL"));
        }

        // Kural 2: Doluluk > %90 → SECTOR_CRITICAL
        if (event.getFillRate() > criticalThreshold) {
            return Optional.of(buildAlert(event, "SECTOR_CRITICAL", "HIGH", "REDIRECT_CRANE"));
        }

        // Kural 3: Doluluk > %85 → SECTOR_WARNING
        if (event.getFillRate() > warningThreshold) {
            return Optional.of(buildAlert(event, "SECTOR_WARNING", "MEDIUM", "MONITOR_SECTOR"));
        }

        // Normal durum — alarm üretme
        log.debug("Sector {} is nominal (fillRate={})", event.getSector(), event.getFillRate());
        return Optional.empty();
    }

    private CongestionAlertEvent buildAlert(ProcessedTelemetryEvent event,
                                             String alertType, String severity,
                                             String recommendedAction) {
        log.warn("[{}] ALERT: {} for sector {} (fillRate={})",
            event.getCorrelationId(), alertType, event.getSector(), event.getFillRate());

        return CongestionAlertEvent.builder()
            .correlationId(event.getCorrelationId())
            .alertType(alertType)
            .sector(event.getSector())
            .severity(severity)
            .fillRate(event.getFillRate())
            .recommendedAction(recommendedAction)
            .timestamp(Instant.now().toString())
            .build();
    }
}
```

### 5.8 RabbitMQ Consumer & Producer

```java
// messaging/consumer/TelemetryConsumer.java
@Component
@RequiredArgsConstructor
@Slf4j
public class TelemetryConsumer {

    private final CongestionRuleEngine ruleEngine;
    private final CongestionAlertProducer alertProducer;

    @RabbitListener(queues = RabbitMQConfig.TELEMETRY_PROCESSED_QUEUE)
    public void onTelemetryReceived(ProcessedTelemetryEvent event) {
        MDC.put("correlationId", event.getCorrelationId());
        try {
            log.info("Received telemetry for sector {} (fillRate={})",
                event.getSector(), event.getFillRate());

            ruleEngine.evaluate(event)
                .ifPresent(alertProducer::publishAlert);

        } finally {
            MDC.clear();
        }
    }
}

// messaging/producer/CongestionAlertProducer.java
@Component
@RequiredArgsConstructor
@Slf4j
public class CongestionAlertProducer {

    private final RabbitTemplate rabbitTemplate;

    public void publishAlert(CongestionAlertEvent alert) {
        rabbitTemplate.convertAndSend(
            RabbitMQConfig.EXCHANGE,
            RabbitMQConfig.CONGESTION_ALERT_QUEUE,
            alert
        );
        log.info("[{}] Alert published: {} for sector {}",
            alert.getCorrelationId(), alert.getAlertType(), alert.getSector());
    }
}
```

---

## 6. EMİRHAN — API Gateway

### 6.1 Sorumluluk

Sisteme giren tüm REST trafiğinin tek giriş noktası. Correlation ID üretimi, rate limiting ve downstream servislere yönlendirme.

### 6.2 Teknoloji Yığını

| Katman | Teknoloji |
|---|---|
| Framework | Spring Cloud Gateway 4.x |
| Reaktif model | Project Reactor (WebFlux) |
| Port | 8080 |

### 6.3 Maven Bağımlılıkları

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-starter-gateway</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
</dependencies>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-dependencies</artifactId>
            <version>2023.0.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

### 6.4 `application.yml`

```yaml
server:
  port: 8080

spring:
  application:
    name: api-gateway
  cloud:
    gateway:
      routes:
        - id: vessel-service
          uri: http://vessel-service:8081
          predicates:
            - Path=/api/vessels/**
          filters:
            - StripPrefix=1           # /api prefix'ini soyar
            - name: RequestRateLimiter
              args:
                redis-rate-limiter.replenishRate: 10
                redis-rate-limiter.burstCapacity: 20

        - id: task-service
          uri: http://task-assignment-service:8084
          predicates:
            - Path=/api/tasks/**
          filters:
            - StripPrefix=1

      default-filters:
        - name: CorrelationIdFilter   # Custom filter — aşağıda tanımlı
```

### 6.5 Correlation ID Global Filter

```java
// filter/CorrelationIdGatewayFilter.java
@Component
public class CorrelationIdGatewayFilter implements GlobalFilter, Ordered {

    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String correlationId = exchange.getRequest().getHeaders()
            .getFirst(CORRELATION_HEADER);

        // Yoksa yeni bir tane üret
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = "OP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        }

        final String finalCorrelationId = correlationId;

        ServerWebExchange mutatedExchange = exchange.mutate()
            .request(req -> req.header(CORRELATION_HEADER, finalCorrelationId))
            .response(res -> res.getHeaders().add(CORRELATION_HEADER, finalCorrelationId))
            .build();

        return chain.filter(mutatedExchange);
    }

    @Override
    public int getOrder() { return -1; }   // En yüksek öncelik
}
```

### 6.6 Logging Filter

```java
// filter/RequestLoggingFilter.java
@Component
@Slf4j
public class RequestLoggingFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String correlationId = exchange.getRequest().getHeaders()
            .getFirst("X-Correlation-ID");
        String method = exchange.getRequest().getMethod().name();
        String path = exchange.getRequest().getPath().toString();

        log.info("[Gateway] {} {} correlationId={}", method, path, correlationId);
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() { return 0; }
}
```

---

## 7. DENİZ — Telemetry Service

### 7.1 Sorumluluk

Drone simülatöründen ham telemetri alır, doğrular, son drone state'ini Redis'e yazar ve işlenmiş event'i `telemetry.processed` kuyruğuna basar.

> **Uygulama güncellemesi:** Bu repo implementasyonunda Telemetry Service **Go 1.22** ile yazılmıştır. Orijinal raporlarda yer alan Spring Boot örnekleri tarihsel tasarım referansı olarak kalmıştır; güncel kaynak kodu `telemetry-service/main.go`, `go.mod` ve Go testleridir. Drone simülatörü ayrı, basit bir Python script olarak korunmuştur.

### 7.2 Teknoloji Yığını

| Katman | Teknoloji | Versiyon |
|---|---|---|
| Runtime | Go | 1.22 |
| HTTP | `net/http` | stdlib |
| Cache | Redis | 7 |
| Redis Client | `github.com/redis/go-redis/v9` | 9.x |
| Mesajlaşma | `github.com/rabbitmq/amqp091-go` | 1.x |
| Port | 8082 | — |

### 7.3 Maven Bağımlılıkları

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-redis</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-amqp</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>
</dependencies>
```

### 7.4 `application.yml`

```yaml
server:
  port: 8082

spring:
  application:
    name: telemetry-service
  data:
    redis:
      host: localhost
      port: 6379
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest

harborsync:
  redis:
    drone-state-ttl-seconds: 30   # Her drone kaydı 30 saniye sonra expire olur
  queues:
    telemetry-processed: telemetry.processed
```

### 7.5 DTO'lar

```java
// dto/RawTelemetryPayload.java  — Drone simülatöründen gelen ham veri
@Data
@NoArgsConstructor
public class RawTelemetryPayload {
    @NotBlank
    private String droneId;
    @NotBlank
    private String sector;
    @Min(0)
    private int containerCount;
    @Min(1)
    private int capacity;
    private boolean blockageDetected;
    @NotBlank
    private String timestamp;
    private String vesselEta;      // Opsiyonel — yaklaşan gemi varsa dolu
}

// dto/ProcessedTelemetryEvent.java — Kuyruğa basılan event
@Data
@Builder
public class ProcessedTelemetryEvent {
    private String correlationId;
    private String sector;
    private double fillRate;        // containerCount / capacity
    private boolean blockageDetected;
    private String droneId;
    private String vesselEta;
    private String timestamp;
}
```

### 7.6 Redis Konfigürasyonu

```java
// config/RedisConfig.java
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        return template;
    }
}
```

### 7.7 Telemetry Service Katmanı

```java
// service/TelemetryService.java
@Service
@RequiredArgsConstructor
@Slf4j
public class TelemetryService {

    private final RedisTemplate<String, String> redisTemplate;
    private final TelemetryProducer telemetryProducer;
    private final ObjectMapper objectMapper;

    @Value("${harborsync.redis.drone-state-ttl-seconds}")
    private long droneTtlSeconds;

    public void process(RawTelemetryPayload payload, String correlationId) {
        // 1. Doğrulama — capacity 0 olamaz (ZeroDivisionError önlemi)
        if (payload.getCapacity() <= 0) {
            throw new IllegalArgumentException("Capacity must be > 0 for drone " + payload.getDroneId());
        }

        // 2. Doluluk oranını hesapla
        double fillRate = (double) payload.getContainerCount() / payload.getCapacity();

        // 3. Son drone state'ini Redis'e yaz (TTL: 30 saniye)
        String redisKey = "drone:" + payload.getDroneId();
        try {
            String stateJson = objectMapper.writeValueAsString(payload);
            redisTemplate.opsForValue().set(redisKey, stateJson, droneTtlSeconds, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            log.warn("[{}] Redis write failed for drone {}: {}", correlationId, payload.getDroneId(), e.getMessage());
        }

        // 4. İşlenmiş event'i RabbitMQ'ya yayınla
        ProcessedTelemetryEvent event = ProcessedTelemetryEvent.builder()
            .correlationId(correlationId)
            .sector(payload.getSector())
            .fillRate(fillRate)
            .blockageDetected(payload.isBlockageDetected())
            .droneId(payload.getDroneId())
            .vesselEta(payload.getVesselEta())
            .timestamp(payload.getTimestamp())
            .build();

        telemetryProducer.publish(event);
        log.info("[{}] Telemetry processed: sector={} fillRate={}", correlationId, payload.getSector(), fillRate);
    }
}
```

### 7.8 REST Controller (Drone simülatöründen HTTP alımı)

```java
// controller/TelemetryController.java
@RestController
@RequestMapping("/telemetry")
@RequiredArgsConstructor
public class TelemetryController {

    private final TelemetryService telemetryService;

    @PostMapping("/ingest")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void ingest(
            @Valid @RequestBody RawTelemetryPayload payload,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        if (correlationId == null) correlationId = UUID.randomUUID().toString();
        telemetryService.process(payload, correlationId);
    }
}
```

### 7.9 Drone Simülatörü (Python Script)

Dosya: `drone-simulator/simulate.py`

```python
import requests
import json
import time
import random
from datetime import datetime

TELEMETRY_URL = "http://localhost:8082/telemetry/ingest"
SECTORS = ["A-01", "B-12", "C-07", "D-04"]
DRONES = ["HD-01", "HD-07", "HD-11"]

def generate_telemetry():
    sector = random.choice(SECTORS)
    capacity = 100
    container_count = random.randint(70, 100)
    return {
        "droneId": random.choice(DRONES),
        "sector": sector,
        "containerCount": container_count,
        "capacity": capacity,
        "blockageDetected": random.random() < 0.15,   # %15 ihtimalle tıkanıklık
        "timestamp": datetime.utcnow().isoformat(),
        "vesselEta": "14:30" if random.random() < 0.3 else None
    }

if __name__ == "__main__":
    print("Drone simulator started. Press Ctrl+C to stop.")
    while True:
        payload = generate_telemetry()
        try:
            r = requests.post(TELEMETRY_URL, json=payload, timeout=2)
            print(f"[{payload['droneId']}] sector={payload['sector']} "
                  f"fill={payload['containerCount']}% → {r.status_code}")
        except Exception as e:
            print(f"Simulator error: {e}")
        time.sleep(2)
```

---

## 8. DENİZ — Task Assignment Service

### 8.1 Sorumluluk

`congestion.alert` kuyruğunu dinler, Vessel Service'ten gelen gemi durumunu sorgular ve vinç/ekip görev ataması yapar. **Bu servis en karmaşık servistir; Saga Pattern ve Circuit Breaker içerir.**

### 8.2 Teknoloji Yığını

| Katman | Teknoloji | Versiyon |
|---|---|---|
| Framework | Spring Boot 3 | 3.2.x |
| ORM | Spring Data JPA | — |
| Veritabanı | PostgreSQL 15 | — |
| Circuit Breaker | Resilience4j | 2.x |
| HTTP Client | Spring WebClient | — |
| Mesajlaşma | Spring AMQP | — |
| Port | 8084 | — |

### 8.3 Maven Bağımlılıkları

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-amqp</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-webflux</artifactId>
        <!-- WebClient için -->
    </dependency>
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>io.github.resilience4j</groupId>
        <artifactId>resilience4j-spring-boot3</artifactId>
    </dependency>
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>
</dependencies>
```

### 8.4 Veritabanı Şeması

Dosya: `src/main/resources/db/migration/V1__create_tasks_table.sql`

```sql
CREATE TABLE tasks (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sector          VARCHAR(20) NOT NULL,
    alert_type      VARCHAR(30) NOT NULL,
    assigned_unit   VARCHAR(50) NOT NULL,
    priority        VARCHAR(10) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    correlation_id  VARCHAR(100),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMP
);

ALTER TABLE tasks ADD CONSTRAINT chk_task_status
    CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED'));

ALTER TABLE tasks ADD CONSTRAINT chk_task_priority
    CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW'));

CREATE INDEX idx_task_status ON tasks(status);
CREATE INDEX idx_task_sector ON tasks(sector);
```

### 8.5 Anti-Corruption Layer — VesselServiceClient

Bu adapter, Task Assignment'ı Vessel Service'in iç modeline karşı izole eder.

```java
// client/VesselServiceClient.java
@Component
@RequiredArgsConstructor
@Slf4j
public class VesselServiceClient {

    private final WebClient webClient;

    @CircuitBreaker(name = "vessel-service", fallbackMethod = "getArrivingVesselsFallback")
    public List<VesselResponse> getArrivingVessels() {
        return webClient.get()
            .uri("/vessels?status=ARRIVING")
            .retrieve()
            .bodyToFlux(VesselResponse.class)
            .collectList()
            .block();
    }

    /**
     * Circuit Breaker açıksa önbellekteki son bilinen liste döner.
     * Şimdilik basit fallback — production'da Redis cache kullanılır.
     */
    public List<VesselResponse> getArrivingVesselsFallback(Throwable t) {
        log.warn("Vessel Service unavailable, using fallback. Reason: {}", t.getMessage());
        return List.of();   // Boş liste — görev yine de oluşturulur, berth null kalır
    }
}

// config/WebClientConfig.java
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient vesselServiceClient(
            @Value("${vessel.service.url:http://vessel-service:8081}") String baseUrl) {
        return WebClient.builder()
            .baseUrl(baseUrl)
            .build();
    }
}
```

### 8.6 `application.yml` (Resilience4j dahil)

```yaml
server:
  port: 8084

spring:
  application:
    name: task-assignment-service
  datasource:
    url: jdbc:postgresql://localhost:5434/task_db
    username: harbor
    password: harbor123
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest

vessel:
  service:
    url: http://vessel-service:8081

resilience4j:
  circuitbreaker:
    instances:
      vessel-service:
        register-health-indicator: true
        sliding-window-size: 5
        minimum-number-of-calls: 3
        failure-rate-threshold: 50         # %50 hata → devre açılır
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 2
```

### 8.7 Task Assignment Consumer (Saga Pattern)

```java
// messaging/consumer/CongestionAlertConsumer.java
@Component
@RequiredArgsConstructor
@Slf4j
public class CongestionAlertConsumer {

    private final TaskAssignmentService taskService;

    @RabbitListener(queues = "congestion.alert")
    public void onAlertReceived(CongestionAlertEvent alert) {
        MDC.put("correlationId", alert.getCorrelationId());
        try {
            log.info("[{}] Alert received: {} for sector {}",
                alert.getCorrelationId(), alert.getAlertType(), alert.getSector());
            taskService.handleAlert(alert);
        } finally {
            MDC.clear();
        }
    }
}

// service/TaskAssignmentService.java
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class TaskAssignmentService {

    private final TaskRepository taskRepository;
    private final VesselServiceClient vesselClient;
    private final TaskCreatedProducer taskCreatedProducer;

    /**
     * Saga adımları:
     * 1. Vessel Service'ten gelen gemileri sorgula (Circuit Breaker korumalı)
     * 2. Görevi oluştur ve kaydet
     * 3. task.created event'i yayınla
     * Herhangi bir adımda hata → görev FAILED olarak kaydedilir (compensating transaction)
     */
    public void handleAlert(CongestionAlertEvent alert) {
        // Saga Adım 1: Mevcut gemileri sorgula
        List<VesselResponse> arrivingVessels = vesselClient.getArrivingVessels();
        String assignedUnit = determineAssignedUnit(alert, arrivingVessels);

        // Saga Adım 2: Görevi kaydet
        Task task = new Task();
        task.setSector(alert.getSector());
        task.setAlertType(alert.getAlertType());
        task.setAssignedUnit(assignedUnit);
        task.setPriority(alert.getSeverity());
        task.setStatus(TaskStatus.PENDING);
        task.setCorrelationId(alert.getCorrelationId());

        Task savedTask = taskRepository.save(task);
        log.info("[{}] Task created: id={} unit={} sector={}",
            alert.getCorrelationId(), savedTask.getId(), assignedUnit, alert.getSector());

        // Saga Adım 3: Event yayınla
        taskCreatedProducer.publish(TaskCreatedEvent.builder()
            .correlationId(alert.getCorrelationId())
            .taskId(savedTask.getId())
            .sector(alert.getSector())
            .assignedUnit(assignedUnit)
            .priority(alert.getSeverity())
            .build());
    }

    private String determineAssignedUnit(CongestionAlertEvent alert, List<VesselResponse> vessels) {
        // Basit atama mantığı — production'da kaynak havuzu yönetimi olur
        return switch (alert.getAlertType()) {
            case "SECTOR_CRITICAL", "IMMEDIATE_ACTION" -> "Crane-" + (int)(Math.random() * 5 + 1);
            default -> "Team-" + (int)(Math.random() * 3 + 1);
        };
    }
}
```

### 8.8 REST Controller

```java
// controller/TaskController.java
@RestController
@RequestMapping("/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskRepository taskRepository;

    @GetMapping("/pending")
    public List<Task> getPendingTasks() {
        return taskRepository.findByStatus(TaskStatus.PENDING);
    }

    @PutMapping("/{id}/complete")
    public Task completeTask(@PathVariable UUID id) {
        Task task = taskRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Task not found: " + id));
        task.setStatus(TaskStatus.COMPLETED);
        task.setCompletedAt(LocalDateTime.now());
        return taskRepository.save(task);
    }
}
```

---

## 9. DENİZ — Notification Service

### 9.1 Sorumluluk

`congestion.alert`, `task.created` ve `dlq.errors` kuyruklarını dinler; tüm olayları yapılandırılmış log formatında `alerts.log` dosyasına yazar. REST endpoint gerekmez.

### 9.2 Teknoloji Yığını

| Katman | Teknoloji | Versiyon |
|---|---|---|
| Framework | FastAPI | 0.110.x |
| Python | 3.11 | — |
| RabbitMQ Client | aio-pika (async) | 9.x |
| Log | Python logging + RotatingFileHandler | — |
| Port | 8085 | — |

### 9.3 `requirements.txt`

```
fastapi==0.110.0
uvicorn==0.29.0
aio-pika==9.4.1
pydantic==2.6.4
python-json-logger==2.0.7
```

### 9.4 Proje Yapısı

```
notification-service/
├── Dockerfile
├── requirements.txt
├── main.py
├── config.py
├── consumer.py
├── formatter.py
└── logs/
    └── alerts.log
```

### 9.5 `config.py`

```python
import os

RABBITMQ_URL = os.getenv("RABBITMQ_URL", "amqp://guest:guest@localhost:5672/")

QUEUES = {
    "congestion_alert": "congestion.alert",
    "task_created": "task.created",
    "dlq": "dlq.errors",
}

LOG_FILE = "logs/alerts.log"
```

### 9.6 `formatter.py` — Yapılandırılmış Log

```python
import logging
import logging.handlers
from config import LOG_FILE
import os

os.makedirs("logs", exist_ok=True)

def get_logger(name: str) -> logging.Logger:
    logger = logging.getLogger(name)
    logger.setLevel(logging.DEBUG)

    # Dosya handler — 10MB, 5 yedek
    file_handler = logging.handlers.RotatingFileHandler(
        LOG_FILE, maxBytes=10_000_000, backupCount=5
    )
    file_handler.setLevel(logging.DEBUG)

    # Console handler
    console_handler = logging.StreamHandler()
    console_handler.setLevel(logging.INFO)

    fmt = "[%(asctime)s] [%(levelname)-8s] [%(correlation_id)s] %(message)s"
    formatter = logging.Formatter(fmt, datefmt="%Y-%m-%d %H:%M:%S")
    file_handler.setFormatter(formatter)
    console_handler.setFormatter(formatter)

    logger.addHandler(file_handler)
    logger.addHandler(console_handler)
    return logger
```

### 9.7 `consumer.py` — Async RabbitMQ Consumer

```python
import json
import aio_pika
from formatter import get_logger
from config import RABBITMQ_URL, QUEUES

logger = get_logger("notification")


def _log(level: str, correlation_id: str, message: str):
    extra = {"correlation_id": correlation_id or "N/A"}
    getattr(logger, level)(message, extra=extra)


async def handle_congestion_alert(message: aio_pika.IncomingMessage):
    async with message.process():
        try:
            payload = json.loads(message.body)
            corr_id = payload.get("correlationId", "N/A")
            alert_type = payload.get("alertType", "UNKNOWN")
            sector = payload.get("sector", "?")
            fill_rate = payload.get("fillRate", 0)

            _log("warning", corr_id,
                 f"[{alert_type}] Sector {sector} at {fill_rate*100:.0f}% capacity")
        except Exception as e:
            logger.error(f"Failed to process congestion.alert: {e}", extra={"correlation_id": "ERR"})


async def handle_task_created(message: aio_pika.IncomingMessage):
    async with message.process():
        try:
            payload = json.loads(message.body)
            corr_id = payload.get("correlationId", "N/A")
            unit = payload.get("assignedUnit", "?")
            sector = payload.get("sector", "?")
            priority = payload.get("priority", "?")

            _log("info", corr_id,
                 f"[TASK] {unit} assigned to {sector} (priority: {priority})")
        except Exception as e:
            logger.error(f"Failed to process task.created: {e}", extra={"correlation_id": "ERR"})


async def handle_dlq(message: aio_pika.IncomingMessage):
    async with message.process():
        msg_id = message.message_id or "unknown"
        _log("error", "DLQ", f"Failed message requeued: msg-id={msg_id}")


async def start_consumers():
    connection = await aio_pika.connect_robust(RABBITMQ_URL)
    channel = await connection.channel()
    await channel.set_qos(prefetch_count=10)

    congestion_q = await channel.declare_queue(QUEUES["congestion_alert"], durable=True)
    task_q = await channel.declare_queue(QUEUES["task_created"], durable=True)
    dlq_q = await channel.declare_queue(QUEUES["dlq"], durable=True)

    await congestion_q.consume(handle_congestion_alert)
    await task_q.consume(handle_task_created)
    await dlq_q.consume(handle_dlq)

    logger.info("Notification Service consumers started.", extra={"correlation_id": "INIT"})
    return connection
```

### 9.8 `main.py`

```python
import asyncio
from contextlib import asynccontextmanager
from fastapi import FastAPI
from consumer import start_consumers
from formatter import get_logger

logger = get_logger("notification.main")


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Uygulama başlarken consumer'ları başlat
    connection = await start_consumers()
    yield
    # Uygulama kapanırken bağlantıyı kapat
    await connection.close()


app = FastAPI(title="HarborSync Notification Service", lifespan=lifespan)


@app.get("/health")
def health():
    return {"status": "UP", "service": "notification-service"}
```

### 9.9 `Dockerfile` (Notification Service)

```dockerfile
FROM python:3.11-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

RUN mkdir -p logs

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8085"]
```

---

## 10. Servislerarası İletişim Sözleşmeleri

> Bu bölüm her iki geliştirici için ortak referanstır. Herhangi bir DTO değiştirildiğinde diğer tarafın bilgilendirilmesi zorunludur.

### 10.1 RabbitMQ Event Şemaları

#### `telemetry.processed` kuyruğu
```json
{
  "correlationId": "OP-2025-001",
  "sector": "B-12",
  "fillRate": 0.94,
  "blockageDetected": true,
  "droneId": "HD-07",
  "vesselEta": "14:30",
  "timestamp": "2025-01-15T14:28:00Z"
}
```

#### `congestion.alert` kuyruğu
```json
{
  "correlationId": "OP-2025-001",
  "alertType": "SECTOR_CRITICAL",
  "sector": "B-12",
  "severity": "HIGH",
  "fillRate": 0.94,
  "recommendedAction": "REDIRECT_CRANE",
  "timestamp": "2025-01-15T14:28:03Z"
}
```

#### `task.created` kuyruğu
```json
{
  "correlationId": "OP-2025-001",
  "taskId": "550e8400-e29b-41d4-a716",
  "sector": "B-12",
  "assignedUnit": "Crane-3",
  "priority": "HIGH",
  "timestamp": "2025-01-15T14:28:04Z"
}
```

### 10.2 REST API Sözleşmeleri

#### Vessel Service `POST /vessels`
```
Request:  { "name": "MV-Ankara", "imoNumber": "IMO1234567", "eta": "2025-01-15T14:00:00" }
Response: { "id": "uuid", "name": "MV-Ankara", "status": "ARRIVING", "berth": null, "eta": "..." }
```

#### Vessel Service `GET /vessels?status=ARRIVING`
```
Response: [ { "id": "...", "name": "...", "status": "ARRIVING", "berth": null } ]
```

#### Task Assignment `GET /tasks/pending`
```
Response: [ { "id": "...", "sector": "B-12", "assignedUnit": "Crane-3", "priority": "HIGH", "status": "PENDING" } ]
```

---

## 11. Test Stratejisi

### 11.1 Her Servis İçin Minimum Test

```
src/test/
├── unit/
│   └── service/             # İş mantığı testleri (mock kullanılır)
└── integration/
    └── messaging/           # RabbitMQ ile entegrasyon (Testcontainers)
```

### 11.2 Congestion Analysis Unit Test Örneği

```java
@ExtendWith(MockitoExtension.class)
class CongestionRuleEngineTest {

    @InjectMocks
    CongestionRuleEngine ruleEngine;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(ruleEngine, "criticalThreshold", 0.90);
        ReflectionTestUtils.setField(ruleEngine, "warningThreshold", 0.85);
    }

    @Test
    void whenFillRateAboveCritical_shouldReturnCriticalAlert() {
        var event = new ProcessedTelemetryEvent("CID-1", "B-12", 0.94, false, "HD-07", null, "...");
        var result = ruleEngine.evaluate(event);
        assertTrue(result.isPresent());
        assertEquals("SECTOR_CRITICAL", result.get().getAlertType());
    }

    @Test
    void whenFillRateBelowWarning_shouldReturnEmpty() {
        var event = new ProcessedTelemetryEvent("CID-2", "A-01", 0.80, false, "HD-01", null, "...");
        var result = ruleEngine.evaluate(event);
        assertTrue(result.isEmpty());
    }

    @Test
    void whenBlockageAndVesselArriving_shouldReturnImmediateAction() {
        var event = new ProcessedTelemetryEvent("CID-3", "C-07", 0.70, true, "HD-11", "14:30", "...");
        var result = ruleEngine.evaluate(event);
        assertTrue(result.isPresent());
        assertEquals("IMMEDIATE_ACTION", result.get().getAlertType());
    }
}
```

### 11.3 Manuel Demo Test Senaryosu

```bash
# 1. Sistemi ayağa kaldır
docker-compose up --build

# 2. Gemi kaydı oluştur
curl -X POST http://localhost:8080/api/vessels \
  -H "Content-Type: application/json" \
  -d '{"name":"MV-Ankara","imoNumber":"IMO1234567","eta":"2025-01-15T14:00:00"}'

# 3. Kritik telemetri gönder (doluluk %94)
curl -X POST http://localhost:8082/telemetry/ingest \
  -H "Content-Type: application/json" \
  -d '{"droneId":"HD-07","sector":"B-12","containerCount":94,"capacity":100,"blockageDetected":true,"timestamp":"2025-01-15T14:28:00","vesselEta":"14:30"}'

# 4. Görev oluşturuldu mu kontrol et
curl http://localhost:8080/api/tasks/pending

# 5. Notification loglarını kontrol et
docker logs harborsync-notification-service

# 6. RabbitMQ Management UI üzerinden kuyrukları izle
# → http://localhost:15672 (guest/guest)
```

Beklenen log çıktısı:
```
[14:28:03] [WARNING ] [OP-XXXXXXXX] [SECTOR_CRITICAL] Sector B-12 at 94% capacity
[14:28:04] [INFO    ] [OP-XXXXXXXX] [TASK] Crane-3 assigned to B-12 (priority: HIGH)
```

---

## 12. Geliştirme Takvimi

| Hafta | Emirhan | Deniz |
|---|---|---|
| **1** | Vessel Service — DB şema + temel CRUD | Telemetry Service — Redis + ingest endpoint |
| **1** | Docker Compose altyapısı | Docker Compose altyapısı |
| **2** | Congestion Analysis — RabbitMQ consumer/producer + kural motoru | Task Assignment — DB şema + alert consumer |
| **2** | API Gateway — routing + Correlation ID filter | Notification Service — consumer + log formatter |
| **3** | Vessel Service unit testleri | Task Assignment — Circuit Breaker + Saga |
| **3** | Congestion Analysis unit testleri | Drone simülatörü |
| **4** | End-to-end demo akışı testi | End-to-end demo akışı testi |
| **4** | `README.md` + mimari diyagram | `README.md` + mimari diyagram |

---

*HarborSync Geliştirici El Kitabı | CENG-442 | Emirhan Ersoy & Deniz Yaldız*
