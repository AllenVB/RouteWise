# backend/

Spring Boot 3.4+ · Java 21 · Gradle

**Durum:** Faz 2 — auth, kargo, adres doğrulama, alıcı, plan/optimizasyon (tek araç), koşu detayı.

Testler (Gradle wrapper yok; Docker ile, Testcontainers için soket bağlanır):

```bash
docker run --rm -v "$(pwd -W):/src" -v routewise-gradle-cache:/home/gradle/.gradle \n  -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \n  -w /src gradle:8.12-jdk21 gradle test --no-daemon
```

Planlanan paket düzeni (paket-özellik yaklaşımı; her özellik kendi
entity/repository/service/controller katmanını içinde taşır):

```
com/routewise/
  config/           Security · Jackson · OpenApi · RestClient
  common/           error/ validation/ geo/
  auth/             JWT · login · rol çözümleme
  user/ courier/ recipient/
  depot/ vehicle/
  address/
    verification/   durum makinesi · geofence · yola snap
  shipment/         sipariş no üretimi
  plan/
  routing/
    matrix/         DistanceMatrixProvider · cache · Dijkstra
    graph/          pgRouting referans sorguları
  optimization/
    client/         PythonOptimizerClient
    service/        koşu yönetimi · kalıcılaştırma
  comparison/ analytics/ notification/
```

Ayrıntı: [../docs/roadmap.md](../docs/roadmap.md) — Faz 1a
