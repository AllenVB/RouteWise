--liquibase formatted sql

-- Faz 3 — maliyet modeli.

--changeset routewise:032-priority-penalty-seed context:!test
--comment Öncelik cezası 25 -> 2,5 TL/(öncelik·saat)
-- 25 ile ceza, TL maliyetin 2-3 katına çıkıyor ve optimizasyon öncelikli kargoyu
-- birkaç dakika erkene almak için ek araç açıyordu: gerçek maliyet SAVINGS'in
-- üstüne çıkıyordu (gerçek grafta n=50: 1.527 TL'ye karşı 1.260 TL). 2,5 ile
-- öncelik sırayı etkiler ama para maliyetini ezmez. Elle değiştirilmiş bir
-- değerin üzerine yazılmasın diye yalnızca seed değeri hâlâ duruyorsa güncellenir.
UPDATE cost_parameters SET priority_penalty = 2.50
WHERE effective_from = DATE '2026-01-01' AND priority_penalty = 25.00;
--rollback UPDATE cost_parameters SET priority_penalty = 25.00 WHERE effective_from = DATE '2026-01-01' AND priority_penalty = 2.50;

--changeset routewise:032-route-costs
--comment Rota bazında yakıt ve TL maliyet — F7 maliyet kırılımı
ALTER TABLE routes ADD COLUMN total_fuel_l   NUMERIC(10,3);
ALTER TABLE routes ADD COLUMN total_cost_try NUMERIC(12,2);
--rollback ALTER TABLE routes DROP COLUMN total_cost_try; ALTER TABLE routes DROP COLUMN total_fuel_l;
