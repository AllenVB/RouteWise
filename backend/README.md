# backend/

Spring Boot 3.4+ · Java 21 · Gradle

**Durum: boş — Faz 1a'da kurulacak.**

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
