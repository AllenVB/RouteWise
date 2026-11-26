--liquibase formatted sql

-- change_count_today hangi güne ait olduğunu tutmuyordu: sayaç hiç sıfırlanmıyor,
-- günlük limite bir kez ulaşan adres kalıcı olarak kilitleniyordu.
-- Tarihi boş olan mevcut satırlar eski gün sayılır, yani kilitleri açılır.

--changeset routewise:030-address-change-date
--comment Günlük adres değişiklik sayacının ait olduğu gün
ALTER TABLE addresses ADD COLUMN change_count_date DATE;
--rollback ALTER TABLE addresses DROP COLUMN change_count_date;
