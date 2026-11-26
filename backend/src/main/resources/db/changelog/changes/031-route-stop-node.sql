--liquibase formatted sql

-- Durağın koşu anındaki yol grafı düğümü. Kargonun adresi sonradan taşınabilir;
-- düğüm burada saklanmazsa eski bir koşunun geometrisi ve mesafesi yeni adresten
-- yeniden hesaplanır ve ölçüm sessizce değişir.

--changeset routewise:031-route-stop-node
--comment Durağın koşu anındaki graf düğümü
ALTER TABLE route_stops ADD COLUMN node_id BIGINT;
--rollback ALTER TABLE route_stops DROP COLUMN node_id;
