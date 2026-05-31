# HarborSync Emirhan Task Event Log

## 2026-05-31 21:13:22 +03

Emirhan'a assign edilen issue'lari (#3 Vessel Service, #4 Congestion Analysis, #5 API Gateway) uygulamak icin calisma baslatildi. Rapor ve gelistirici el kitabindaki stack esas alinacak: Vessel Service icin Spring Boot 3 + PostgreSQL + Flyway, Congestion Analysis icin Spring Boot 3 + RabbitMQ/stateless rule engine, API Gateway icin Spring Cloud Gateway + correlation ID + routing + rate limiting.

Karar: Uc servis birbirinden bagimsiz klasorlerde oldugu icin agent'larla paralel uygulanacak. Ana surec entegrasyon kontrolu, event log ve final dogrulamadan sorumlu kalacak.

## 2026-05-31 21:17:22 +03

Congestion Analysis Service tamamlandi. Neden: Issue #4, Telemetry Service tarafindan yayinlanan `telemetry.processed` olaylarini okuyup liman sektorleri icin tikaniklik karari vermeyi ve gerekirse `congestion.alert` olayi uretmeyi istiyordu. Nasil: Spring Boot 3.2.5 + Spring AMQP projesi kuruldu; `ProcessedTelemetryEvent` ve `CongestionAlertEvent` DTO'lari rapordaki JSON sozlesmesine gore yazildi; `RabbitMqConfig` icinde `harborsync.exchange`, `telemetry.processed`, `congestion.alert` ve `dlq.errors` tanimlandi.

Kural motoru stateless tutuldu. Bu rapordaki yatay olceklenebilirlik kararina uyuyor: karar sadece gelen event icindeki `fillRate`, `blockageDetected` ve `vesselEta` alanlarina bakiyor. Kurallar: `blockageDetected=true` ve `vesselEta` doluysa `IMMEDIATE_ACTION/HIGH/HOLD_VESSEL`; `fillRate > 0.90` ise `SECTOR_CRITICAL/HIGH/REDIRECT_CRANE`; `fillRate > 0.85` ise `SECTOR_WARNING/MEDIUM/MONITOR_SECTOR`; aksi halde alarm yok. Critical, warning, immediate ve nominal senaryolari icin unit test eklendi.

## 2026-05-31 21:17:59 +03

API Gateway tamamlandi. Neden: Issue #5, sistemde REST trafiginin merkezi giris noktasini, correlation ID tasimasini ve rate limiting davranisini istiyordu. Nasil: Spring Cloud Gateway projesi kuruldu; `/api/vessels/**` istekleri Vessel Service'e, `/api/tasks/**` istekleri Task Assignment Service'e yonlendirildi. `X-Correlation-ID` yoksa UUID uretiliyor, downstream request'e ve response header'a yaziliyor. Request logging filter eklendi.

Rate limiting icin Redis tabanli Spring Cloud Gateway RequestRateLimiter konfiguru edildi. Raporla uyumlu olarak replenish rate 10 req/s, burst capacity 20 secildi. Bu tercih API Gateway'i halka acik tek giris noktasi kabul eden mimariye uyuyor ve servislerin dogrudan yuk altinda kalmasini azaltmayi hedefliyor.

## 2026-05-31 21:17:59 +03

Vessel Service tamamlandi. Neden: Issue #3, gemi yasam dongusunun sistemdeki tek dogru kaynagi olacak servisi istiyordu. Nasil: Spring Boot 3 + PostgreSQL + Flyway projesi kuruldu; `vessels` tablosu, status constraint'i ve index eklendi. `Vessel` entity, `VesselStatus`, DTO'lar, repository, service, REST controller, exception handler ve correlation ID filter yazildi.

Endpointler rapordaki sozlesmeye gore hazirlandi: `POST /vessels`, `GET /vessels`, `GET /vessels?status=...`, `GET /vessels/{id}`, `PUT /vessels/{id}/status`. IMO numarasi icin duplicate kontrolu eklendi; bulunamayan gemiler 404, duplicate IMO 409, validation hatalari 400 donuyor. Minimal service ve controller testleri eklendi.

## 2026-05-31 21:19:21 +03

Emirhan servisleri icin son statik dogrulama yapildi. `git diff --check` temiz. Tum Maven POM dosyalari XML olarak parse edildi. `docker compose config --quiet` basariyla calisti; yalniz Docker Compose, rapordaki `version: "3.8"` alaninin yeni Compose surumlerinde obsolete oldugunu uyari olarak yazdi. Bu uyari calismayi engellemiyor.

Maven bu ortamda bulunmadigi icin Java testleri calistirilamadi (`mvn: command not found`). Java testleri Maven kurulu bir ortamda su komutlarla kosulmali: `mvn -f vessel-service/pom.xml test`, `mvn -f congestion-analysis/pom.xml test`, `mvn -f gateway/pom.xml test`.
