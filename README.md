# RouteWise

**Smart Dynamic Route & Fleet Optimization**

Lojistik Dağıtım Süreçlerinde Dinamik Rota ve Araç Optimizasyonu Sağlayan Akıllı Yazılım Sistemi

---

## Amaç

Kargo ve lojistik dağıtım süreçlerinde oluşan **yakıt, zaman ve araç kullanım maliyetlerini**
azaltmak amacıyla dinamik rota optimizasyonu gerçekleştiren akıllı bir yazılım sistemi geliştirmek.

## Özgün Yanı

Sistem yalnızca en kısa rotayı bulmaz. Dağıtım sürecini birden fazla değişkeni
**birlikte** değerlendirerek optimize eder:

- Araç kapasitesi
- Teslimat öncelikleri
- Mesafe ve tahmini süre
- Yakıt tüketimi ve toplam maliyet

## Mimari

Web tabanlı, servis ayrımlı bir mimari. Ayrıntılar: [Sistem Mimarisi](docs/architecture.md)

| Bileşen | Teknoloji | Sorumluluk |
|---|---|---|
| **Backend** | Java 21 + Spring Boot 3.4+ | Domain yönetimi, veri sahipliği, matris cache'i, analizler |
| **Optimizer** | Python 3.12 + FastAPI | Rota optimizasyon algoritması (durumsuz, DB erişimi yok) |
| **Frontend** | React 19 + Vite + TypeScript + MapLibre | Yönetim arayüzü, harita görselleştirme, raporlar |
| **Veritabanı** | PostgreSQL + PostGIS | Coğrafi veri, dağıtım geçmişi, performans metrikleri |
| **Mesafe verisi** | OpenRouteService API | Mesafe/süre matrisi (`DistanceMatrixProvider` arkasında; OSRM'e geçiş açık) |

### Çözülen Problem

**MDVRPTW** — Multi-Depot Vehicle Routing Problem with Time Windows. Çok depo, araç
kapasitesi ve teslimat zaman penceresi sert kısıt; teslimat önceliği, yakıt ve sürücü
maliyeti amaç fonksiyonunda **birlikte** değerlendirilir.

Optimizasyon algoritması hazır bir çözücü değil, projeye özgü olarak geliştirilir:
Solomon I1 insertion ile başlangıç çözümü, ardından zaman olurluluğunu koruyan yerel
arama hamleleri (relocate, exchange, Or-opt, 2-opt\*) üzerinde simulated annealing.

Sistem üç katman hâlinde inşa edilir ve her katman çalışan bir sistemdir:
**CVRP → VRPTW → MDVRPTW**.

Çözüm kalitesi iki şekilde ölçülür: her koşuda hesaplanan naive baseline rotaya karşı
tasarruf yüzdesi, ve literatürdeki standart test kümelerine (Solomon, Cordeau) karşı
en iyi bilinen çözümden sapma (gap %).

## Gelişim Planı

- [ ] Proje gereksinimlerinin ve kullanım senaryolarının belirlenmesi
- [ ] Sistem mimarisinin ve veritabanı tasarımının oluşturulması
- [ ] Kullanıcı, araç, teslimat ve konum yönetimi modüllerinin geliştirilmesi
- [ ] Rota oluşturma ve rota optimizasyon algoritmasının sisteme dahil edilmesi
- [ ] Teslimat tercihlerine göre dinamik rota güncelleme mekanizmasının geliştirilmesi
- [ ] Yakıt, mesafe, süre ve maliyet hesaplama modüllerinin oluşturulması
- [ ] Haftalık, aylık ve yıllık performans analizlerinin geliştirilmesi
- [ ] Farklı dağıtım senaryoları üzerinde testlerin gerçekleştirilmesi
- [ ] Sistem performansının ve elde edilen tasarruf oranlarının değerlendirilmesi
- [ ] Son testler, dokümantasyon ve proje raporunun tamamlanması

## Başarı Kriterleri

1. Teslimat noktalarına göre otomatik ve optimize edilmiş rotaların oluşturulabilmesi
2. Teslimat bilgilerindeki değişikliklerde rotanın yeniden oluşturulabilmesi
3. Araç kapasitesi ve teslimat önceliklerinin rota planlamasına dahil edilebilmesi
4. Toplam mesafe ve tahmini yakıt tüketiminin hesaplanabilmesi
5. Optimize edilmiş rota ile standart rota arasındaki mesafe, süre ve maliyet farkının ölçülebilmesi
6. Gerçekleştirilen dağıtımlara ait verilerin saklanabilmesi
7. Haftalık, aylık ve yıllık tasarruf analizlerinin raporlanabilmesi
8. Sistemin farklı araç ve dağıtım senaryolarında çalıştırılabilmesi
9. Geleneksel rota planlamasına kıyasla ölçülebilir bir verimlilik artışı sağlanması

## Dokümantasyon

- [Proje Önerisi](docs/proposal.md)
