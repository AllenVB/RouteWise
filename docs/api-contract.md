# API Sözleşmesi

> Sürüm: v1 · 15.10.2026 · Faz 1c
> Uçlar [ekran prototipinden](prototype/index.html) türetilmiştir. Ekranın ihtiyacı
> olmayan uç yazılmaz; karşılıksız uç bırakılmaz (§7 izlenebilirlik tablosu).

## 1. Temel kurallar

| Konu | Karar |
|---|---|
| Taban yol | `/api/v1` |
| Biçim | JSON, UTF-8 |
| Kimlik | `Authorization: Bearer <JWT>` |
| Tarih/saat | ISO-8601, UTC (`2026-10-15T07:40:00Z`) |
| Tarih | `2026-10-15` |
| Para | `NUMERIC`, kuruş değil TL (`1548.00`), birim alan adında (`costTry`) |
| Mesafe | metre (`distanceM`) · Süre: saniye (`durationS`) · Ağırlık: kg (`weightKg`) |
| Koordinat | `{ "lat": 40.3521, "lon": 27.9767 }` — WGS84 |
| Alan adları | `camelCase` |

Birim her zaman alan adının sonunda taşınır. `distance` yerine `distanceM` yazmak,
"bu km miydi metre miydi" tartışmasını baştan bitirir.

## 2. Hata biçimi

Tüm hatalar tek gövde ile döner:

```json
{
  "code": "ADDRESS_NOT_VERIFIED",
  "message": "Adres doğrulanmadığı için kargo plana alınamaz.",
  "fieldErrors": [
    { "field": "addressId", "message": "Adres DOGRULAMA_BEKLIYOR durumunda." }
  ],
  "traceId": "c7f1a4e2",
  "timestamp": "2026-10-15T07:40:00Z"
}
```

`fieldErrors` yalnızca doğrulama hatalarında dolar; diğer hatalarda boş dizidir.
`message` **kullanıcıya gösterilebilir** Türkçe metindir: ne olduğunu ve ne yapılacağını
söyler, özür dilemez.

### Hata kodları

| HTTP | `code` | Ne zaman |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Gövde doğrulaması başarısız |
| 401 | `UNAUTHENTICATED` | Token yok veya süresi dolmuş |
| 403 | `FORBIDDEN` | Rol bu kaynağa erişemez |
| 404 | `NOT_FOUND` | Kayıt yok |
| 409 | `ADDRESS_NOT_VERIFIED` | Doğrulanmamış adresli kargo plana alınmak istendi |
| 409 | `ADDRESS_LOCKED` | Teslimat `YOLDA` iken adres değiştirilmek istendi |
| 409 | `CHANGE_LIMIT_REACHED` | Günlük adres değişikliği limiti aşıldı |
| 409 | `PLAN_ALREADY_DISPATCHED` | Sevk edilmiş plan yeniden optimize edilmek istendi |
| 409 | `NO_FEASIBLE_SOLUTION` | Kapasite/süre kısıtlarıyla olurlu çözüm yok |
| 409 | `PLAN_NOT_OPTIMIZED` | Sevk için uygulanan koşu yok ya da koşudan sonra kargolar değişti |
| 409 | `NOT_ENOUGH_COURIERS` | Rota sayısı kadar müsait kurye yok |
| 409 | `SHIPMENT_IN_RUN_HISTORY` | Kargo kayıtlı bir koşuda geçiyor; ölçüm geçmişi korunur, silinemez |
| 422 | `OUTSIDE_SERVICE_AREA` | Nokta Bandırma sınırı dışında |
| 422 | `TOO_FAR_FROM_ROAD` | Nokta en yakın yola eşikten uzak |
| 502 | `OPTIMIZER_UNAVAILABLE` | Python servisine ulaşılamıyor |
| 504 | `OPTIMIZER_TIMEOUT` | Optimizasyon zaman aşımına uğradı |

409'lar iş kuralı ihlalidir; istemci gövdeyi düzelterek değil, **akışı değiştirerek**
çözer. 422'ler coğrafi doğrulama sonucudur.

## 3. Sayfalama, sıralama, filtreleme

Liste uçları aynı sözleşmeyi paylaşır:

```
GET /api/v1/shipments?page=0&size=25&sort=createdAt,desc&status=BEKLEMEDE&q=BND-2026
```

| Parametre | Varsayılan | Not |
|---|---|---|
| `page` | 0 | sıfır tabanlı |
| `size` | 25 | en fazla 100 |
| `sort` | kaynağa göre | `alan,asc\|desc` |
| `q` | — | serbest metin (sipariş no, alıcı adı, adres) |

Yanıt zarfı:

```json
{
  "content": [ ... ],
  "page": 0, "size": 25, "totalElements": 142, "totalPages": 6
}
```

## 4. Kimlik doğrulama

| Metot | Yol | Rol | İş |
|---|---|---|---|
| POST | `/auth/login` | herkes | `{email, password}` → `{accessToken, refreshToken, role, displayName}` |
| POST | `/auth/refresh` | herkes | `{refreshToken}` → yeni çift |
| POST | `/auth/logout` | kimliği doğrulanmış | refresh token'ı geçersizler |
| GET | `/auth/me` | kimliği doğrulanmış | oturum sahibinin profili |

Rol token içindedir; istemci rol bilgisini gövdeden okur ama **yetki her istekte sunucuda
yeniden doğrulanır**. İstemciye güvenilmez.

## 5. Uçlar

### 5.1 Kargo firması

| Metot | Yol | Ekran | İş |
|---|---|---|---|
| GET | `/dashboard?date=` | F2 | Gün özeti: kargo sayıları, mesafe, maliyet, bekleyen doğrulama |
| GET | `/shipments` | F3 | Kargo listesi (sayfalı, filtreli) |
| POST | `/shipments` | F4 | Kargo oluştur — sipariş no sunucuda üretilir |
| GET | `/shipments/{id}` | F3 | Tek kargo |
| PATCH | `/shipments/{id}` | F3 | Ağırlık, öncelik, teslim süresi güncelle |
| DELETE | `/shipments/{id}` | F3 | Planlanmamış kargoyu sil |
| GET | `/recipients` | F4 | Alıcı arama (`q`) |
| GET | `/recipients/{id}/addresses` | F4 | Alıcının adresleri + doğrulama durumu |
| GET | `/addresses?status=` | F5 | Doğrulama kuyruğu |
| GET | `/addresses/{id}` | F5 | Adres detayı: konum, snap mesafesi, kontrol sonuçları |
| POST | `/addresses/{id}/verify` | F5 | Onayla |
| POST | `/addresses/{id}/reject` | F5 | `{reason}` ile reddet |
| GET | `/plans?date=` | F6 | Günün planı (yoksa 404) |
| POST | `/plans` | F6 | `{date}` ile plan aç |
| POST | `/plans/{id}/optimize` | F6 | RouteWise + naive baseline koşar → `runId` |
| GET | `/plans/{id}/runs` | F6 | Plana ait koşular |
| GET | `/runs/{id}` | F6 | Koşu metrikleri + rotalar |
| POST | `/plans/{id}/dispatch` | F6 | Uygulanan koşuyu kuryelere sevk et |
| GET | `/routes/{id}` | F7 | Rota detayı: duraklar, ETA, yük, maliyet |
| POST | `/plans/{id}/compare/tour` | F8 | Seviye 2 — tüm tur algoritmaları |
| POST | `/compare/path` | F8 | Seviye 1 — `{fromNodeId, toNodeId}` |
| GET | `/comparisons/{id}` | F8 | Karşılaştırma sonucu |
| GET | `/analytics/savings?period=&from=&to=` | F9 | `period=WEEK\|MONTH\|YEAR` |
| GET | `/analytics/export?format=&period=` | F9 | `format=XLSX\|PDF` → dosya |
| GET | `/vehicles` | F6 | Filo (salt okunur, seed) |

### 5.2 Kurye

| Metot | Yol | Ekran | İş |
|---|---|---|---|
| GET | `/courier/route?date=` | K2, K4 | Kuryenin günlük rotası, duraklar ve geometri |
| GET | `/courier/stops/{id}` | K3 | Durak detayı |
| POST | `/courier/stops/{id}/deliver` | K3 | Teslim edildi |
| POST | `/courier/stops/{id}/fail` | K3 | `{reason}` — ulaşılamadı |
| GET | `/courier/route/changes?since=` | K5 | Rota değişiklikleri: çıkan, eklenen, sırası değişen duraklar |

`deliver` çağrısı sistemin kuryenin konumunu bilmesini sağlar: dinamik yeniden
rotalama **son tamamlanan duraktan** başlar, canlı GPS kullanılmaz.

### 5.3 Alıcı

| Metot | Yol | Ekran | İş |
|---|---|---|---|
| GET | `/me/shipments` | A2 | Alıcının kargoları |
| GET | `/me/shipments/{id}` | A3 | Kargo detayı + ETA + durum geçmişi |
| POST | `/me/shipments/{id}/postpone` | A3 | `{date}` — başka gün gelsin |
| GET | `/me/addresses` | A4 | Alıcının adresleri |
| POST | `/me/addresses` | A4 | Yeni adres |
| PUT | `/me/addresses/{id}` | A4 | Adresi güncelle → durum `DOGRULAMA_BEKLIYOR`'a düşer |
| GET | `/me/notifications` | A5 | Bildirimler |
| POST | `/me/notifications/read` | A5 | `{ids}` veya `{all:true}` |

### 5.4 Ortak

| Metot | Yol | Rol | İş |
|---|---|---|---|
| POST | `/addresses/validate` | firma, alıcı | **Kaydetmeden** konum kontrolü: geofence, yola snap, ters geocoding |
| GET | `/system/status` | kimliği doğrulanmış | Backend + Python servisi + yol grafı durumu |

`POST /addresses/validate` ekranlardan doğdu: hem F5'te hem A4'te kullanıcı haritaya
tıkladığı anda geri bildirim görmeli, kaydet düğmesine basmadan önce. Kaydetme ile
doğrulamayı ayırmak, hatalı noktanın veritabanına hiç girmemesini sağlar.

## 6. Yetki matrisi

| Uç grubu | Firma | Kurye | Alıcı |
|---|---|---|---|
| `/auth/**` | ✓ | ✓ | ✓ |
| `/dashboard`, `/shipments/**`, `/plans/**`, `/runs/**`, `/routes/**` | ✓ | — | — |
| `/addresses/**` (doğrulama) | ✓ | — | — |
| `/compare/**`, `/comparisons/**`, `/analytics/**` | ✓ | — | — |
| `/vehicles` | ✓ | — | — |
| `/courier/**` | ✓ (okuma) | ✓ (kendi) | — |
| `/me/**` | — | — | ✓ (kendi) |
| `/addresses/validate` | ✓ | — | ✓ |
| `/system/status` | ✓ | ✓ | ✓ |

**"kendi" kısıtı sunucuda zorlanır.** `/me/shipments/{id}` çağrısında `{id}` başkasının
kargosuysa 404 döner — 403 değil. Sebep: 403, kaynağın var olduğunu sızdırır.

Test stratejisinde her uç için **rol sızıntısı testi** zorunludur.

## 7. İzlenebilirlik — ekran ↔ uç

Her ekranın ihtiyacı karşılanıyor mu, karşılıksız uç var mı?

| Ekran | Uçlar |
|---|---|
| F1 / K1 / A1 Giriş | `POST /auth/login` |
| F2 Gösterge paneli | `GET /dashboard` |
| F3 Kargo listesi | `GET /shipments`, `PATCH /shipments/{id}`, `DELETE /shipments/{id}` |
| F4 Yeni kargo | `GET /recipients`, `GET /recipients/{id}/addresses`, `POST /shipments` |
| F5 Adres doğrulama | `GET /addresses?status=`, `GET /addresses/{id}`, `POST .../verify`, `POST .../reject` |
| F6 Rota planlama | `GET /plans`, `POST /plans`, `POST /plans/{id}/optimize`, `GET /runs/{id}`, `GET /vehicles`, `POST /plans/{id}/dispatch` |
| F7 Rota detayı | `GET /routes/{id}` |
| F8 Karşılaştırma | `POST /plans/{id}/compare/tour`, `POST /compare/path`, `GET /comparisons/{id}` |
| F9 Tasarruf raporları | `GET /analytics/savings`, `GET /analytics/export` |
| K2 Bugünkü rotam | `GET /courier/route` |
| K3 Durak detayı | `GET /courier/stops/{id}`, `POST .../deliver`, `POST .../fail` |
| K4 Harita | `GET /courier/route` (geometri dahil) |
| K5 Rota değişti | `GET /courier/route/changes` |
| A2 Kargolarım | `GET /me/shipments` |
| A3 Kargo detayı | `GET /me/shipments/{id}`, `POST .../postpone` |
| A4 Adresim | `GET /me/addresses`, `POST /me/addresses`, `PUT /me/addresses/{id}`, `POST /addresses/validate` |
| A5 Bildirimler | `GET /me/notifications`, `POST /me/notifications/read` |

**Karşılıksız uç:** yok. `/system/status` tek istisnadır ve teşhis amaçlıdır.
**Karşılanmayan ekran:** yok.

## 8. Örnek gövdeler

### Kargo oluşturma

```http
POST /api/v1/shipments
```
```json
{ "recipientId": 12, "addressId": 31, "weightKg": 4.2, "priority": 2, "serviceTimeMin": 5 }
```
```json
{
  "id": 417, "orderNumber": "BND-2026-00417",
  "recipient": { "id": 12, "fullName": "Elif Demir" },
  "address": { "id": 31, "text": "Yeni Mahalle, 1204 Sk. No:7", "verificationStatus": "DOGRULANDI" },
  "weightKg": 4.2, "priority": 2, "status": "BEKLEMEDE",
  "createdAt": "2026-10-15T07:40:00Z"
}
```

Sipariş numarası istekte **gönderilmez**. Gönderilirse yok sayılır.

### Konum doğrulama (kaydetmeden)

```http
POST /api/v1/addresses/validate
```
```json
{ "lat": 40.3521, "lon": 27.9767 }
```
```json
{
  "withinServiceArea": true,
  "snappedNodeId": 48219,
  "snapDistanceM": 7.4,
  "reverseGeocoded": "Yeni Mahalle, 1204 Sk.",
  "outlier": false,
  "acceptable": true,
  "warnings": []
}
```

Kabul edilmeyen nokta için `acceptable:false` ve `warnings` dolu döner; HTTP yine 200'dür
çünkü istek başarıyla **değerlendirilmiştir**. Kaydetme denemesinde 422 döner.

### Optimizasyon

```http
POST /api/v1/plans/88/optimize
```
```json
{ "algorithm": "ROUTEWISE", "randomSeed": 42, "warmStartFromRunId": 311 }
```
```json
{
  "runId": 312, "algorithm": "ROUTEWISE", "isApplied": true,
  "totalDistanceM": 103500, "totalDurationS": 25920,
  "totalFuelL": 9.5, "totalCostTry": 1548.00, "vehicleCount": 2,
  "computationTimeMs": 1840, "randomSeed": 42,
  "baseline": { "runId": 313, "algorithm": "NAIVE", "totalCostTry": 2196.00 },
  "savingsPct": 29.5,
  "routes": [ { "id": 901, "vehicleId": 1, "courierId": 3, "stopCount": 14 } ]
}
```

`randomSeed` yanıtta **her zaman** döner: tohum olmadan sonuç tekrar üretilemez ve
tez ölçümü savunulamaz.

**Faz 3 sürümü (30.10.2026)** — Faz 2 notlarının yerini alır:

- gövde isteğe bağlı: `{ "algorithm": "ROUTEWISE" | "SAVINGS" | "GREEDY_NN" | "NAIVE",
  "vehicleId": 3, "randomSeed": 42, "warmStartFromRunId": 311 }`; varsayılan ROUTEWISE ve
  tüm aktif filo. `vehicleId` yalnızca o araçla planlar (tek araç senaryosu).
  `randomSeed` / `warmStartFromRunId` yalnızca ROUTEWISE ile; başkasıyla 400
- maliyet plan gününde geçerli `cost_parameters` satırıyla hesaplanır
- `totalCostTry` gerçek paradır: yakıt + sürücü + araç sabit gideri. `priorityPenalty`
  amaçtaki gecikme cezasıdır, para değildir ve maliyete eklenmez
- `savingsPct` TL maliyet üzerinden naive baseline'a göre; `distanceSavingsPct` korunur
- `randomSeed` ROUTEWISE koşusunda her zaman döner (verilmediyse üretilen); diğerlerinde null
- `GET /runs/{id}` rotalarında `totalFuelL`, `totalCostTry` vardır

**Faz 2 sürümü (30.10.2026)** — tarihçe için:

- gövde isteğe bağlıdır: `{ "algorithm": "GREEDY_NN" | "NAIVE", "vehicleId": 2 }` —
  varsayılan `GREEDY_NN` ve en büyük kapasiteli aktif araç; diğer algoritmalar 400
- `totalCostTry`, `totalFuelL`, `randomSeed` **null** döner
- tasarruf **mesafe** üzerinden `distanceSavingsPct` adıyla döner. Maliyet bazlı
  `savingsPct` Faz 3'te eklenir; aynı adı şimdi mesafe için kullanmak anlamını sonradan
  sessizce değiştirirdi
- `excluded[]` — rotaya alınmayan kargolar: `{shipmentId, orderNumber, reason}`,
  `reason` = `ADDRESS_NOT_VERIFIED` (adres onaysız ya da onaydan sonra taşınmış),
  `NO_ROAD_NODE` (onaylı ama yol grafına hiç bağlanmamış — doğrulama akışı dışında
  yazılmış kayıt) veya `UNREACHABLE` (depoyla gidiş-dönüş yolu yok)
- `GET /runs/{id}` araç başına sıralı duraklar + GeoJSON `LineString` geometri döner;
  rota servisi erişilemezse duraklar yine gelir, `geometryAvailable: false` olur
- `POST /plans` aynı gün için idempotenttir: yeni plan 201, mevcut plan 200

## 9. Faz 1c kapsamı

Bu belge **sözleşmedir**; uçların gövdeleri Faz 2'den itibaren yazılır.

| Durum | İş |
|---|---|
| ✅ | Sözleşme yazıldı, ekran izlenebilirliği doğrulandı |
| ✅ | Ortak hata biçimi ve kod kataloğu |
| ✅ | Swagger/OpenAPI arayüzü açıldı |
| ✅ | Postman collection üretildi |
| ✅ Faz 2 | `/auth/**`, `/shipments/**`, `/addresses/**`, `/recipients/**`, `/vehicles` |
| ✅ Faz 2 (öne alındı) | `/plans` (aç/getir), `/plans/{id}/optimize` tek araç, `/plans/{id}/runs`, `/runs/{id}` |
| ✅ Faz 2 (öne alındı) | `/me/addresses` GET/POST/PUT — adres oluşturan başka uç yok, Faz 2 DoD'si bunu gerektiriyor |
| ✅ Faz 3 | çok araç + maliyet, `/plans/{id}/dispatch` (otomatik kurye ataması), `GET /routes/{id}` |
| Faz 4 | `/courier/**`, `/me/**` (kalanlar) |
| Faz 6 | `/compare/**`, `/analytics/**` |
