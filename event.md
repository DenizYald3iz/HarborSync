# HarborSync Development Event Log

## 2026-05-31 20:52:55 +03

Calismaya Deniz'e assign edilen issue'lari (#6-#11) bitirmek icin basladik. Once raporlardaki Deniz bolumleri tekrar okundu: Telemetry Service, Task Assignment Service, Notification Service ve Drone Simulator kapsamlarinin Spring Boot 3, FastAPI, RabbitMQ, Redis, PostgreSQL ve Resilience4j uzerine kuruldugu dogrulandi.

Karar: Isi paralel bolduk. Curie agent'i `notification-service` ve `drone-simulator` dosyalarindan sorumlu. Linnaeus agent'i `task-assignment-service` dosyalarindan sorumlu. Ana surec `telemetry-service`, ortak kontrol, entegrasyon ve bu gunlugu yonetecek. Bu bolumleme dosya cakismasini azaltmak ve Deniz'e assign edilen issue'lari daha hizli tamamlamak icin yapildi.

## 2026-05-31 20:53:00 +03

Ortam kontrol edildi. `docker` var, fakat `mvn` bulunamadi. Bu nedenle Maven projeleri standart `pom.xml` ve Dockerfile yapisiyla yazilacak; lokal Java build dogrulamasi icin Maven kullanilamayabilir. Gerekirse dogrulama `docker build` veya sadece statik dosya incelemesiyle sinirli kalacak.

## 2026-05-31 20:55:25 +03

Telemetry Service uygulandi. Neden: Issue #6 Deniz'e atanmis ve raporda drone verisinin sisteme giris noktasi olarak tanimlanmis. Nasil: Spring Boot 3.2.5 Maven projesi, `POST /telemetry/ingest` endpointi, Bean Validation, Redis TTL state yazimi, RabbitMQ `telemetry.processed` publisher'i, direct exchange binding'i ve DLQ ayarlari eklendi. `X-Correlation-ID` varsa kullaniliyor, yoksa UUID uretiliyor; boylece sonraki servisler ve loglar ayni operasyonu izleyebiliyor.

Teknik not: `capacity` icin DTO seviyesinde `@Min(1)` var, servis katmaninda da tekrar kontrol var. Bu bilincli tekrar, HTTP disindan servis cagirilirsa da sifira bolme riskini engellemek icin eklendi.

## 2026-05-31 20:57:21 +03

Notification Service ve Drone Simulator agent tarafindan tamamlandi. Neden: Issue #10 Notification Service'in olaylari tek yerde loglamasini, issue #11 ise demo verisi ureten drone scriptini istiyordu. Nasil: FastAPI uygulamasi `congestion.alert`, `task.created` ve `dlq.errors` kuyruklarini `aio-pika.connect_robust` ile dinleyecek sekilde yazildi. Loglama `RotatingFileHandler` ile `notification-service/logs/alerts.log` dosyasina timestamp, level, correlation_id ve mesaj seklinde kaydediliyor. Drone simulator env override destekliyor ve Telemetry Service gecici olarak kapaliysa hata yazip calismaya devam ediyor.

Karar: Notification Service kuyruklari kendisi declare/bind ediyor. Bu, servis ilk kez tek basina ayaga kalktiginda da gerekli queue'larin RabbitMQ uzerinde bulunmasini saglar. Ortak sozlesmeyle uyum icin exchange adi `harborsync.exchange`, queue adlari rapordaki degerlerle ayni tutuldu.

## 2026-05-31 21:01:34 +03

Task Assignment Service agent tarafindan uretildi ve ana surecte gozden gecirildi. Neden: Issue #7, #8 ve #9 bu servisin `congestion.alert` olaylarini goreve cevirmesini, Vessel Service ile korumali sekilde konusmasini ve `task.created` olayi yayinlamasini istiyordu. Nasil: Spring Boot 3.2.5 projesi, PostgreSQL/Flyway `tasks` semasi, JPA repository, RabbitMQ consumer/producer, WebClient tabanli VesselServiceClient, Resilience4j Circuit Breaker, saga benzeri servis akisi ve REST controller eklendi.

Karar: Vessel Service erisilemezse Circuit Breaker fallback bos vessel listesi donduruyor. Bu, liman alarmi geldigi anda gorev uretimini tamamen durdurmamak icin secildi. Bu durumda atama rapordaki basit mantikla `Crane-1` veya `Team-1` gibi varsayilan birime yapiliyor. `task.created` publish islemi basarisiz olursa gorev `FAILED` yapiliyor; boylece veritabaninda olay yayinlanamayan isler gorulebilir.

Duzeltme: Agent yanlislikla `task-assignment-service/event.md` olusturmustu. Proje gunlugunun tek kaynak olmasi icin servis altindaki dosya kaldirildi ve o bilgi ana `event.md` icine tasindi. Ayrica Task REST API icin MockMvc controller testi eklendi; pending listeleme, complete endpointi ve not-found cevabi kapsandi.

## 2026-05-31 21:02:14 +03

Dogrulama yapildi. `python3 -m py_compile` ile Notification Service ve Drone Simulator Python dosyalarinin syntax kontrolu gecti. Drone simulator, bilerek kapali bir endpoint'e (`localhost:9`) tek iterasyonla calistirildi; servis yokken exception'i yakalayip sureci kontrollu tamamladigi goruldu. Notification logger testi timestamp, level, correlation_id ve mesaj formatinda cikti uretti.

`docker compose config` basariyla calisti ve compose yapisinin parse edilebilir oldugunu gosterdi. Docker Compose sadece `version` alaninin artik obsolete olduguna dair uyari verdi; raporda `version: '3.8'` kullanildigi icin simdilik bu alan korunuyor. `mvn` bu ortamda kurulu olmadigi icin Java servislerinde `mvn test` calistirilamadi; Maven kurulu bir ortamda `mvn test` telemetry-service ve task-assignment-service altinda calistirilmalidir.

## 2026-05-31 21:02:44 +03

RabbitMQ DLQ argumanlari hizalandi. Neden: Ayni queue birden fazla servis tarafindan declare edilirse RabbitMQ queue argumanlari birebir ayni degilse `PRECONDITION_FAILED` hatasi verir. Telemetry Service ve rapordaki ornekler `x-dead-letter-exchange` icin default exchange'i temsil eden bos string kullaniyordu. Task Assignment tarafinda da DLQ argumani `""` yapildi; routing key `dlq.errors` olarak kaldi. Boylece `congestion.alert` ve `task.created` queue'lari Notification Service ile ayni argumanlarla declare edilebilir.
