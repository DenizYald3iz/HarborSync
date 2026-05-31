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

## 2026-05-31 21:08:28 +03

Test yazimi icin iki test muhendisi agent baslatildi. Kepler Python tarafindan (`notification-service`, `drone-simulator`) sorumlu. Lorentz Java/Spring tarafindan (`telemetry-service`, `task-assignment-service`) sorumlu. Neden: Test dosyalari servis bazinda ayrildigi icin paralel calismak cakisma riskini dusuk tutuyor. Hedef testleri abartmadan, juri veya demo oncesi kodun temel davranislarini dogrulayacak kadar kapsam eklemek.

## 2026-05-31 21:14:45 +03

Test kapsamı genisletildi. Python tarafinda Notification Service icin payload decode, invalid payload reject, `congestion.alert` log/ack davranisi ve DLQ metadata loglama test edildi. Drone simulator icin telemetry alan sozlesmesi, servis kapaliyken ana akisin patlamamasi ve `send_telemetry` status code donusu test edildi. `python3 -m unittest discover -s notification-service/tests -v` 4 test, `python3 -m unittest discover -s drone-simulator/tests -v` 3 test olarak basariyla gecti.

Java tarafinda Telemetry Controller icin correlation header ve validation testleri, Task Assignment producer routing testi eklendi. Mevcut Task Assignment service/controller testleriyle birlikte bu testler Maven ortaminda calistirilacak. Bu makinede `mvn` olmadigi icin Java testleri lokal calistirilamadi; ancak POM XML parse kontrolu basariyla gecti.

## 2026-05-31 21:27:49 +03

Acik kalan ortak issue'lar (#1, #2, #12, #13) tekrar degerlendirildi. #1 ve #2 icin altyapi, Docker Compose, `.env.example`, queue/event sozlesmeleri ve DLQ ayarlari zaten tamamlanmisti. #12 ve #13 icin README'ye calistirilabilir demo curl akisi, test komutlari, ekip gorev dagilimi, hafta bazli plan ve bilinen riskler eklendi. Boylece ortak issue'lar kapatilabilir hale getirildi.

## 2026-05-31 21:35:00 +03

Telemetry Service Go'ya tasindi. Neden: Kullanici Telemetry Service'in Go ile yazilmasini istedi ve bu karar eski proposal'da gecen Go fikriyle daha uyumlu. Nasil: Spring Boot/Maven kaynaklari kaldirildi; yerine Go 1.22 tabanli HTTP servis, Redis state writer ve RabbitMQ `telemetry.processed` publisher eklendi. Endpoint sozlesmesi degismedi: `POST /telemetry/ingest` ayni payload'i aliyor, `fillRate` hesapliyor, `X-Correlation-ID` varsa kullaniyor yoksa uretiyor, Redis'e `drone:{droneId}` TTL ile yaziyor ve RabbitMQ'ya JSON event yayinliyor.

Tutarlilik duzeltmeleri: `docker-compose.yml` artik Telemetry Service'e Go servisinin bekledigi `REDIS_ADDR`, `RABBITMQ_URL`, `TELEMETRY_PROCESSED_QUEUE`, `DLQ_QUEUE` ve `DRONE_STATE_TTL_SECONDS` degiskenlerini veriyor. README servis tablosu ve test komutlari Spring Boot/Maven yerine Go 1.22 olarak guncellendi. Bu ortamda `go` kurulu olmadigi icin `go test ./...` lokal calistirilamadi; Docker build veya Go kurulu ortamda calistirilmasi gerekiyor.

## 2026-05-31 21:08:49 +03

Python test calismasina baslandi. Neden: Deniz'e ait Notification Service ve Drone Simulator parcalarinin demo oncesi hizli dogrulanabilir olmasi gerekiyor; testlerin amaci tam entegrasyon yerine kritik davranislari kucuk fake/mock nesnelerle kontrol etmek. Nasil: `unittest` secildi, cunku ek test bagimliligi eklemiyor ve mevcut `requirements.txt` dosyasini sisirmiyor. Notification tarafinda gercek RabbitMQ olmadan fake `IncomingMessage` ve async context manager kullaniliyor; `aio-pika` yerelde kurulu degilse import kirilmasin diye test icinde minimal stub tanimlaniyor. Drone simulator tarafinda telemetry payload sozlesmesi, `send_telemetry` HTTP cagrisi ve servis kapaliyken `MAX_ITERATIONS=1` ana akisinin exception firlatmadan bitmesi test ediliyor.

## 2026-05-31 21:09:08 +03

Java/Spring test kapsami icin minimal test muhendisi turu baslatildi. Neden: Deniz'e atanmis servislerde kod yazildi, fakat juri veya demo oncesi davranisin testlerle kanitlanmasi gerekiyor. Asiri genis entegrasyon testleri yerine kritik sozlesmeleri dogrulayan unit/web-layer testleri secildi; boylece Maven kurulu bir ortamda hizli geri bildirim alinabilir.

Telemetry Service icin `TelemetryControllerTest` eklendi. Nasil: `@WebMvcTest` ve mocked `TelemetryService` ile `POST /telemetry/ingest` endpointinin `X-Correlation-ID` headerini servis katmanina aynen tasidigi, 202 Accepted dondugu ve gecersiz payload icin servis cagrilmadan 400 validation cevabi urettigi dogrulaniyor. Bu test drone simulator ile Telemetry Service arasindaki HTTP sozlesmesini kapsiyor.

Task Assignment Service icin `TaskCreatedProducerTest` eklendi. Nasil: Mock `RabbitTemplate` ile `task.created` olayinin `harborsync.exchange` uzerinden `task.created` routing key'ine gonderildigi dogrulaniyor. Bu, rapordaki event-driven akisin Notification Service ve diger tuketicilerle ayni routing sozlesmesini kullanmasini garanti altina almak icin secildi.

## 2026-05-31 21:10:35 +03

Test dogrulamasi yapildi. `python3 -m unittest discover -s notification-service/tests` 4 testi basariyla calistirdi. `python3 -m unittest discover -s drone-simulator/tests` 3 testi basariyla calistirdi. Java tarafinda `mvn -v` komutu `mvn: command not found` ile bitti; bu nedenle Spring testleri yerelde calistirilamadi, fakat Maven standart test dosyalari olarak yazildi ve Maven kurulu bir ortamda servis dizinlerinden calistirilabilir.

## 2026-05-31 21:10:11 +03

Python testleri calistirildi ve gecti. Neden: Test dosyalarinin sadece yazilmis olmasi yeterli degil; yerel ortamda bagimlilik yuklemeden calisabildigini de kanitlamak gerekiyor. Nasil: `python3 -m unittest discover -s notification-service/tests -v` komutu 4 testi basariyla gecirdi; JSON decode, gecersiz payload reddi, congestion alert log davranisi ve DLQ metadata loglamasi kontrol edildi. `python3 -m unittest discover -s drone-simulator/tests -v` komutu 3 testi basariyla gecirdi; telemetry alanlari, servis kapaliyken tek iterasyonluk ana akis ve HTTP status code donduren `send_telemetry` davranisi kontrol edildi.
