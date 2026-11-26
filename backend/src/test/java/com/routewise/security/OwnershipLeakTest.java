package com.routewise.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import com.routewise.support.ApiTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * "Only your own records": rules the filter cannot express, checked end to end.
 *
 * <p>Recipient A and courier A own a shipment, an address, a notification and a
 * dispatched stop. B of each role must neither see nor change any of it, and the
 * answer must be 404 — a 403 would confirm the record exists.
 */
class OwnershipLeakTest extends ApiTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 4);
    private static final String ORDER = "BND-2026-07001";

    private long addressA;
    private long shipmentA;
    private long notificationA;
    private long courierA;
    private long stopA;

    @BeforeEach
    void seed() {
        long userA = user("a@test", "RECIPIENT");
        long recipientA = recipient(userA);
        recipient(user("b@test", "RECIPIENT"));
        user("firma@test", "COMPANY");
        courierA = courier(user("kurye-a@test", "COURIER"));
        courier(user("kurye-b@test", "COURIER"));

        addressA = jdbc.queryForObject("""
                INSERT INTO addresses (recipient_id, address_text, location, verification_status,
                                       verified_by, verified_at)
                VALUES (?, 'A Sokağı No 1', ST_SetSRID(ST_MakePoint(27.97, 40.35), 4326), 'DOGRULANDI',
                        (SELECT id FROM users WHERE email = 'firma@test'), now())
                RETURNING id
                """, Long.class, recipientA);
        shipmentA = jdbc.queryForObject("""
                INSERT INTO shipments (order_number, recipient_id, address_id, weight_kg, status)
                VALUES (?, ?, ?, 2.5, 'PLANLANDI') RETURNING id
                """, Long.class, ORDER, recipientA, addressA);
        notificationA = jdbc.queryForObject("""
                INSERT INTO notifications (user_id, shipment_id, type, channel, payload)
                VALUES (?, ?, 'ETA', 'IN_APP', '{"orderNumber": "BND-2026-07001"}'::jsonb) RETURNING id
                """, Long.class, userA, shipmentA);

        // Testte yol grafı yok; depo önceden bağlanmış sayılır, yoksa okuma grafı arar.
        long depot = jdbc.queryForObject("""
                INSERT INTO depots (name, location, max_route_duration_min, snapped_node_id)
                VALUES ('Test Depo', ST_SetSRID(ST_MakePoint(27.9767, 40.3521), 4326), 540, 1) RETURNING id
                """, Long.class);
        long vehicle = jdbc.queryForObject("""
                INSERT INTO vehicles (depot_id, plate, capacity_kg, fuel_l_per_100km)
                VALUES (?, '10 S 1', 100, 9) RETURNING id
                """, Long.class, depot);
        long plan = jdbc.queryForObject("""
                INSERT INTO delivery_plans (depot_id, plan_date, status) VALUES (?, ?, 'SEVK_EDILDI') RETURNING id
                """, Long.class, depot, DAY);
        long run = jdbc.queryForObject("""
                INSERT INTO optimization_runs (plan_id, algorithm, is_applied) VALUES (?, 'ROUTEWISE', true) RETURNING id
                """, Long.class, plan);
        long route = jdbc.queryForObject("""
                INSERT INTO routes (run_id, vehicle_id, courier_id) VALUES (?, ?, ?) RETURNING id
                """, Long.class, run, vehicle, courierA);
        stopA = jdbc.queryForObject("""
                INSERT INTO route_stops (route_id, shipment_id, sequence_no) VALUES (?, ?, 1) RETURNING id
                """, Long.class, route, shipmentA);
    }

    @Nested
    @DisplayName("Alıcı")
    class Recipient {

        @Test
        @DisplayName("başkasının kargosu listesinde çıkmaz, sahibininkinde çıkar")
        void shouldListOnlyOwnShipments() throws Exception {
            mvc.perform(get("/api/v1/me/shipments").header("Authorization", bearer("b@test")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
            mvc.perform(get("/api/v1/me/shipments").header("Authorization", bearer("a@test")))
                    .andExpect(jsonPath("$[0].orderNumber").value(ORDER));
        }

        @Test
        @DisplayName("başkasının kargo detayı 404 ve gövdede iz yok")
        void shouldHideOthersShipmentDetail() throws Exception {
            String body = mvc.perform(get("/api/v1/me/shipments/" + shipmentA).header("Authorization", bearer("b@test")))
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain(ORDER).doesNotContain("A Sokağı");
        }

        @Test
        @DisplayName("başkasının adresi listede çıkmaz ve güncellenemez (404)")
        void shouldNotTouchOthersAddress() throws Exception {
            mvc.perform(get("/api/v1/me/addresses").header("Authorization", bearer("b@test")))
                    .andExpect(jsonPath("$.length()").value(0));
            mvc.perform(put("/api/v1/me/addresses/" + addressA).header("Authorization", bearer("b@test"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"addressText\":\"Ele geçirildi\",\"location\":{\"lat\":40.35,\"lon\":27.97}}"))
                    .andExpect(status().isNotFound());

            assertThat(jdbc.queryForObject("SELECT address_text FROM addresses WHERE id = ?", String.class, addressA))
                    .isEqualTo("A Sokağı No 1");
        }

        @Test
        @DisplayName("başkasının bildirimini göremez, okundu işaretleyemez")
        void shouldNotReadOrMarkOthersNotification() throws Exception {
            mvc.perform(get("/api/v1/me/notifications").header("Authorization", bearer("b@test")))
                    .andExpect(jsonPath("$.items.length()").value(0))
                    .andExpect(jsonPath("$.unreadCount").value(0));
            mvc.perform(post("/api/v1/me/notifications/read").header("Authorization", bearer("b@test"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[" + notificationA + "]}"))
                    .andExpect(status().isNoContent());
            mvc.perform(post("/api/v1/me/notifications/read").header("Authorization", bearer("b@test"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"all\":true}"))
                    .andExpect(status().isNoContent());

            assertThat(jdbc.queryForObject("SELECT read_at IS NULL FROM notifications WHERE id = ?",
                    Boolean.class, notificationA)).isTrue();
        }
    }

    @Nested
    @DisplayName("Kurye")
    class Courier {

        @Test
        @DisplayName("kendi durağını okur; başkasınınki 404 ve gövdede iz yok")
        void shouldReadOnlyOwnStop() throws Exception {
            mvc.perform(get("/api/v1/courier/stops/" + stopA).header("Authorization", bearer("kurye-a@test")))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString(ORDER)));

            String body = mvc.perform(get("/api/v1/courier/stops/" + stopA).header("Authorization", bearer("kurye-b@test")))
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain(ORDER).doesNotContain("A Sokağı");
        }

        @Test
        @DisplayName("başkasının durağını kapatamaz; durak açık kalır")
        void shouldNotCloseOthersStop() throws Exception {
            mvc.perform(post("/api/v1/courier/stops/" + stopA + "/deliver").header("Authorization", bearer("kurye-b@test")))
                    .andExpect(status().isNotFound());
            mvc.perform(post("/api/v1/courier/stops/" + stopA + "/fail").header("Authorization", bearer("kurye-b@test"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"deneme\"}"))
                    .andExpect(status().isNotFound());

            assertThat(jdbc.queryForObject("SELECT status FROM route_stops WHERE id = ?", String.class, stopA))
                    .isEqualTo("BEKLEMEDE");
        }

        @Test
        @DisplayName("courierId vererek başkasının rotasını isteyemez; kendi rotası sorulur")
        void shouldIgnoreCourierIdFromCourier() throws Exception {
            mvc.perform(get("/api/v1/courier/route").param("date", DAY.toString())
                            .param("courierId", String.valueOf(courierA))
                            .header("Authorization", bearer("kurye-b@test")))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("Firma durağı okuyabilir ama kapatamaz (403)")
    void companyReadsButCannotCloseStop() throws Exception {
        mvc.perform(get("/api/v1/courier/stops/" + stopA).header("Authorization", bearer("firma@test")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/courier/stops/" + stopA + "/deliver").header("Authorization", bearer("firma@test")))
                .andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("SELECT status FROM route_stops WHERE id = ?", String.class, stopA))
                .isEqualTo("BEKLEMEDE");
    }
}
