--liquibase formatted sql

-- Başlangıç verisi: depo, sabit filo, maliyet parametreleri, test kullanıcıları.
-- Filo "sabit"tir: yönetim ekranı yazılmaz, ama koda gömülmez — burada durur ki
-- "farklı araç senaryolarında çalıştırılabilmesi" kriteri sonradan karşılanabilsin.

--changeset routewise:900-depot context:!test
--comment Bandırma kargo şubesi — konum ilçe merkezi
INSERT INTO depots (name, address_text, location, opening_time, closing_time, max_route_duration_min)
VALUES (
    'Bandırma Şube',
    'Bandırma, Balıkesir',
    ST_SetSRID(ST_MakePoint(27.9767, 40.3521), 4326),
    '08:00', '18:00',
    540  -- 9 saatlik çalışma günü
);
--rollback DELETE FROM depots WHERE name = 'Bandırma Şube';

--changeset routewise:900-vehicles context:!test
--comment Sabit filo — 3 araç, farklı kapasite ve tüketim
INSERT INTO vehicles (depot_id, plate, capacity_kg, capacity_volume_m3, fuel_l_per_100km, fuel_type, fixed_cost_per_day)
SELECT d.id, v.plate, v.cap, v.vol, v.fuel, 'DIESEL', v.fixed
FROM depots d
CROSS JOIN (VALUES
    ('10 ABC 001', 1200.00, 8.0,  9.50, 250.00),
    ('10 ABC 002', 1500.00, 11.0, 11.20, 250.00),
    ('10 ABC 003',  800.00, 5.5,  7.80, 200.00)
) AS v(plate, cap, vol, fuel, fixed)
WHERE d.name = 'Bandırma Şube';
--rollback DELETE FROM vehicles WHERE plate LIKE '10 ABC%';

--changeset routewise:900-cost-parameters context:!test
--comment Başlangıç maliyet parametreleri
INSERT INTO cost_parameters (fuel_price_per_l, driver_cost_per_hour, priority_penalty, effective_from)
VALUES (44.00, 150.00, 25.00, DATE '2026-01-01');
--rollback DELETE FROM cost_parameters WHERE effective_from = DATE '2026-01-01';

--changeset routewise:900-test-users context:!test
--comment Test kullanıcıları — üç rol. Parola hash'i Faz 2'de auth ile birlikte üretilecek.
INSERT INTO users (email, password_hash, full_name, phone, role) VALUES
    ('firma@routewise.local',  'DEGISTIRILECEK', 'Kargo Firması',  '05000000001', 'COMPANY'),
    ('kurye@routewise.local',  'DEGISTIRILECEK', 'Test Kurye',     '05000000002', 'COURIER'),
    ('alici@routewise.local',  'DEGISTIRILECEK', 'Test Alıcı',     '05000000003', 'RECIPIENT');

INSERT INTO couriers (user_id, employee_code)
SELECT id, 'KRY-001' FROM users WHERE email = 'kurye@routewise.local';

INSERT INTO recipients (user_id)
SELECT id FROM users WHERE email = 'alici@routewise.local';
--rollback DELETE FROM users WHERE email LIKE '%@routewise.local';
