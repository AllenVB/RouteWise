package com.routewise.reroute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import com.routewise.common.error.ApiException;
import com.routewise.common.error.ErrorCode;
import com.routewise.optimization.client.OptimizerClient;
import com.routewise.optimization.client.OptimizerDtos.OptimizeRequest;
import com.routewise.optimization.client.OptimizerDtos.OptimizeResult;
import com.routewise.optimization.client.OptimizerDtos.RouteOut;
import com.routewise.optimization.client.OptimizerDtos.SolutionOut;
import com.routewise.optimization.client.OptimizerDtos.StopOut;
import com.routewise.optimization.client.OptimizerDtos.UnassignedOut;
import com.routewise.routing.matrix.DistanceMatrix;
import com.routewise.routing.matrix.DistanceMatrixProvider;
import com.routewise.support.ApiTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Changes to a route on the road, end to end: real filter chain, real database,
 * the optimizer stubbed — what it is asked and what is written back. Rerouting
 * runs synchronously here ({@code routewise.reroute.debounce=0s}).
 *
 * <p>The route: S1 delivered at node 101, S2·S3·S4 still open. Typically the
 * recipient postpones S2, so the courier continues from 101 with S3 and S4.
 */
class RerouteFlowTest extends ApiTest {

    private static final ZoneId TZ = ZoneId.of("Europe/Istanbul");

    @MockitoBean
    private OptimizerClient optimizer;

    @MockitoBean
    private DistanceMatrixProvider matrices;

    private LocalDate today;
    private long recipientUser;
    private long courierUser;
    private long plan;
    private long route;
    private long vehicle;
    private long s1, s2, s3, s4;
    private long stop1, stop2, stop3, stop4;

    @BeforeEach
    void seed() {
        today = LocalDate.now(TZ);
        recipientUser = user("alici@test", "RECIPIENT");
        long recipient = recipient(recipientUser);
        recipient(user("baska@test", "RECIPIENT"));
        user("firma@test", "COMPANY");
        courierUser = user("kurye@test", "COURIER");
        long courier = courier(courierUser);

        // Gün gece yarısı açılır, neredeyse tüm gün sürer: "şimdi" her zaman mesai içindedir.
        long depot = jdbc.queryForObject("""
                INSERT INTO depots (name, location, opening_time, closing_time, max_route_duration_min, snapped_node_id)
                VALUES ('Test Depo', ST_SetSRID(ST_MakePoint(27.9767, 40.3521), 4326), '00:00', '23:59', 1439, 1)
                RETURNING id
                """, Long.class);
        vehicle = jdbc.queryForObject("""
                INSERT INTO vehicles (depot_id, plate, capacity_kg, fuel_l_per_100km, fixed_cost_per_day)
                VALUES (?, '10 R 1', 500, 9, 250) RETURNING id
                """, Long.class, depot);
        jdbc.update("""
                INSERT INTO cost_parameters (fuel_price_per_l, driver_cost_per_hour, priority_penalty, effective_from)
                VALUES (44, 150, 2.5, DATE '2026-01-01')
                """);
        long address = jdbc.queryForObject("""
                INSERT INTO addresses (recipient_id, address_text, location, verification_status, snapped_node_id,
                                       verified_by, verified_at)
                VALUES (?, 'Test Sokağı 1', ST_SetSRID(ST_MakePoint(27.97, 40.35), 4326), 'DOGRULANDI', 101,
                        (SELECT id FROM users WHERE email = 'firma@test'), now())
                RETURNING id
                """, Long.class, recipient);

        plan = jdbc.queryForObject("""
                INSERT INTO delivery_plans (depot_id, plan_date, status) VALUES (?, ?, 'SEVK_EDILDI') RETURNING id
                """, Long.class, depot, today);
        long run = jdbc.queryForObject("""
                INSERT INTO optimization_runs (plan_id, algorithm, is_applied) VALUES (?, 'ROUTEWISE', true) RETURNING id
                """, Long.class, plan);
        route = jdbc.queryForObject("""
                INSERT INTO routes (run_id, vehicle_id, courier_id, status, started_at)
                VALUES (?, ?, ?, 'YOLDA', now()) RETURNING id
                """, Long.class, run, vehicle, courier);

        Instant now = Instant.now();
        s1 = shipment("BND-2026-08001", recipient, address, "TESLIM_EDILDI", 0);
        s2 = shipment("BND-2026-08002", recipient, address, "YOLDA", 0);
        s3 = shipment("BND-2026-08003", recipient, address, "YOLDA", 5);
        s4 = shipment("BND-2026-08004", recipient, address, "YOLDA", 0);
        stop1 = stop(s1, 1, 101, "TESLIM_EDILDI", now.minus(Duration.ofMinutes(30)), now.minus(Duration.ofMinutes(10)));
        stop2 = stop(s2, 2, 102, "BEKLEMEDE", now.plus(Duration.ofMinutes(10)), null);
        stop3 = stop(s3, 3, 103, "BEKLEMEDE", now.plus(Duration.ofMinutes(20)), null);
        stop4 = stop(s4, 4, 104, "BEKLEMEDE", now.plus(Duration.ofHours(5)), null);

        when(matrices.forNodes(anyList())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            return new DistanceMatrix("h", ids, new Double[ids.size()][ids.size()],
                    new Double[ids.size()][ids.size()], 0);
        });
    }

    private long shipment(String order, long recipient, long address, String status, int priority) {
        return jdbc.queryForObject("""
                INSERT INTO shipments (order_number, recipient_id, address_id, weight_kg, priority, status)
                VALUES (?, ?, ?, 2.0, ?, ?) RETURNING id
                """, Long.class, order, recipient, address, priority, status);
    }

    private long stop(long shipment, int seq, long node, String status, Instant eta, Instant actual) {
        return jdbc.queryForObject("""
                INSERT INTO route_stops (route_id, shipment_id, sequence_no, node_id, status, eta, actual_arrival)
                VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, route, shipment, seq, node, status, Timestamp.from(eta),
                actual == null ? null : Timestamp.from(actual));
    }

    /** Optimizer answer visiting the given shipments in that order, {@code 600 s} apart. */
    private OptimizeResult answer(List<Long> order, List<UnassignedOut> unassigned) {
        return priced(order, unassigned, 90.0);
    }

    /** Same, the round costing {@code costTry} (no priority penalty). */
    private OptimizeResult priced(List<Long> order, double costTry) {
        return priced(order, List.of(), costTry);
    }

    private OptimizeResult priced(List<Long> order, List<UnassignedOut> unassigned, double costTry) {
        List<StopOut> out = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            out.add(new StopOut(order.get(i), 100 + i, i + 1, 600.0 * (i + 1), 1000.0, 120.0));
        }
        RouteOut r = new RouteOut(vehicle, out, 900, 90, 4000, 400, 600, 1000, 4, 0.4, 90.0);
        SolutionOut result = new SolutionOut("ROUTEWISE", List.of(r), 4000, 1000, 1, 12.0, 0.4, costTry, 3.0,
                costTry + 3.0);
        SolutionOut kept = new SolutionOut("NAIVE", List.of(r), 4500, 1100, 1, 1.0, 0.45, 100.0, 4.0, 104.0);
        return new OptimizeResult(route, result, kept, null, unassigned);
    }

    private ResultActions postpone(long shipment, LocalDate date, String who) throws Exception {
        return mvc.perform(post("/api/v1/me/shipments/" + shipment + "/postpone")
                .header("Authorization", bearer(who))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"" + date + "\"}"));
    }

    private String stopStatus(long stop) {
        return jdbc.queryForObject("SELECT status FROM route_stops WHERE id = ?", String.class, stop);
    }

    private int seq(long stop) {
        return jdbc.queryForObject("SELECT sequence_no FROM route_stops WHERE id = ?", Integer.class, stop);
    }

    private Instant eta(long stop) {
        Timestamp t = jdbc.queryForObject("SELECT eta FROM route_stops WHERE id = ?", Timestamp.class, stop);
        return t == null ? null : t.toInstant();
    }

    private int suggestionRows() {
        return jdbc.queryForObject("SELECT count(*) FROM postpone_suggestions WHERE route_id = ?", Integer.class, route);
    }

    private int notifications(long user, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND type = ?",
                Integer.class, user, type);
    }

    /** Sequence uniqueness is deferred to commit; the test never commits, so check it now. */
    private void assertSequencesUnique() {
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
    }

    @Nested
    @DisplayName("Yoldaki rota")
    class OnTheRoad {

        @Test
        @DisplayName("ertelenen durak çıkar; kalanlar son teslim edilen duraktan yeniden sıralanır")
        void shouldRerouteFromLastVisitedStop() throws Exception {
            when(optimizer.optimize(any())).thenReturn(answer(List.of(s4, s3), List.of()));
            Instant before = Instant.now();

            postpone(s2, today.plusDays(2), "alici@test")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ERTELENDI"))
                    .andExpect(jsonPath("$.postponedTo").value(today.plusDays(2).toString()));
            Instant after = Instant.now();

            ArgumentCaptor<OptimizeRequest> sent = ArgumentCaptor.forClass(OptimizeRequest.class);
            verify(optimizer).optimize(sent.capture());
            OptimizeRequest req = sent.getValue();
            assertThat(req.startNodeId()).isEqualTo(101L);
            assertThat(req.depotNodeId()).isEqualTo(1L);
            assertThat(req.stops()).extracting(s -> s.id()).containsExactly(s3, s4);
            assertThat(req.vehicles()).singleElement().extracting(v -> v.id()).isEqualTo(vehicle);
            assertThat(req.warmStart()).singleElement().extracting(w -> w.stopIds()).isEqualTo(List.of(s3, s4));
            assertThat(req.maxRouteDurationS()).isNotNull().isPositive();

            assertThat(stopStatus(stop2)).isEqualTo("ERTELENDI");
            assertThat(eta(stop2)).isNull();
            assertThat(List.of(seq(stop1), seq(stop4), seq(stop3), seq(stop2))).containsExactly(1, 2, 3, 4);
            assertThat(eta(stop4)).isBetween(before.plusSeconds(600).minusSeconds(1), after.plusSeconds(600));
            assertThat(eta(stop3)).isBetween(before.plusSeconds(1200).minusSeconds(1), after.plusSeconds(1200));
            assertSequencesUnique();

            assertThat(jdbc.queryForObject("SELECT status FROM shipments WHERE id = ?", String.class, s2))
                    .isEqualTo("ERTELENDI");
            assertThat(jdbc.queryForObject("SELECT status FROM shipments WHERE id = ?", String.class, s4))
                    .isEqualTo("YOLDA");
        }

        @Test
        @DisplayName("kurye bilgilendirilir, değişiklik ölçüm için kaydedilir")
        void shouldNotifyCourierAndRecordEvent() throws Exception {
            when(optimizer.optimize(any())).thenReturn(answer(List.of(s4, s3), List.of()));

            postpone(s2, today.plusDays(1), "alici@test").andExpect(status().isOk());

            assertThat(jdbc.queryForObject("""
                    SELECT payload->>'openStops' FROM notifications WHERE user_id = ? AND type = 'ROTA_DEGISTI'
                    """, String.class, courierUser)).isEqualTo("2");
            var event = jdbc.queryForMap("SELECT * FROM reroute_events WHERE route_id = ?", route);
            assertThat(event.get("trigger_type")).isEqualTo("ERTELEME");
            assertThat(((Number) event.get("shipment_id")).longValue()).isEqualTo(s2);
            assertThat(((Number) event.get("start_node_id")).longValue()).isEqualTo(101L);
            assertThat(event.get("open_stops_before")).isEqualTo(2);
            assertThat(event.get("open_stops_after")).isEqualTo(2);
            assertThat(event.get("kept_order_cost_try").toString()).isEqualTo("100.00");
            assertThat(event.get("new_cost_try").toString()).isEqualTo("90.00");
        }

        @Test
        @DisplayName("ETA'sı 15 dakikadan fazla kayan alıcıya yeni saat bildirilir, az kayana bildirilmez")
        void shouldNotifyOnlyMaterialEtaShifts() throws Exception {
            // S4: eski ETA +5 sa, yeni ≈ +10 dk -> bildirim. S3: eski +20 dk, yeni ≈ +20 dk -> sessiz.
            when(optimizer.optimize(any())).thenReturn(answer(List.of(s4, s3), List.of()));

            postpone(s2, today.plusDays(1), "alici@test").andExpect(status().isOk());

            assertThat(jdbc.queryForList("""
                    SELECT shipment_id FROM notifications WHERE user_id = ? AND type = 'ETA'
                      AND payload->>'updated' = 'true'
                    """, Long.class, recipientUser)).containsExactly(s4);
        }

        @Test
        @DisplayName("kalan güne birlikte sığmıyorlarsa önceliği düşük olan otomatik ertelenir")
        void shouldAutoPostponeLowestPriorityWhenInfeasible() throws Exception {
            when(optimizer.optimize(any()))
                    .thenThrow(new ApiException(ErrorCode.NO_FEASIBLE_SOLUTION, "sığmıyor"))
                    .thenReturn(answer(List.of(s3), List.of()));

            postpone(s2, today.plusDays(1), "alici@test").andExpect(status().isOk());

            ArgumentCaptor<OptimizeRequest> sent = ArgumentCaptor.forClass(OptimizeRequest.class);
            verify(optimizer, times(2)).optimize(sent.capture());
            assertThat(sent.getAllValues().get(1).stops()).extracting(s -> s.id()).containsExactly(s3);

            assertThat(stopStatus(stop4)).isEqualTo("ERTELENDI");
            assertThat(jdbc.queryForMap("SELECT status, postponed_to FROM shipments WHERE id = ?", s4))
                    .containsEntry("status", "ERTELENDI")
                    .containsEntry("postponed_to", Date.valueOf(today.plusDays(1)));
            assertThat(jdbc.queryForObject("""
                    SELECT payload->>'reason' FROM notifications WHERE shipment_id = ? AND type = 'ERTELEME'
                    """, String.class, s4)).isEqualTo("EXCEEDS_WORKDAY");
            assertThat(List.of(seq(stop1), seq(stop3))).containsExactly(1, 2);
            assertThat(jdbc.queryForObject("SELECT auto_postponed FROM reroute_events WHERE route_id = ?",
                    Integer.class, route)).isEqualTo(1);
            assertSequencesUnique();
        }

        @Test
        @DisplayName("optimizer tek başına güne sığmayan durağı dışarıda bırakırsa o da ertelenir")
        void shouldAutoPostponeUnassignedStop() throws Exception {
            when(optimizer.optimize(any())).thenReturn(
                    answer(List.of(s3), List.of(new UnassignedOut(s4, "EXCEEDS_WORKDAY"))));

            postpone(s2, today.plusDays(1), "alici@test").andExpect(status().isOk());

            assertThat(stopStatus(stop4)).isEqualTo("ERTELENDI");
            assertThat(jdbc.queryForObject("""
                    SELECT payload->>'autoPostponed' FROM notifications WHERE user_id = ? AND type = 'ROTA_DEGISTI'
                    """, String.class, courierUser)).contains("BND-2026-08004");
        }

        @Test
        @DisplayName("son açık durak ertelenirse rota ve plan tamamlanır; optimizer çağrılmaz")
        void shouldCompleteRouteWhenLastOpenStopLeaves() throws Exception {
            jdbc.update("UPDATE route_stops SET status = 'TESLIM_EDILDI', actual_arrival = now() WHERE id IN (?, ?)",
                    stop3, stop4);

            postpone(s2, today.plusDays(1), "alici@test").andExpect(status().isOk());

            verify(optimizer, never()).optimize(any());
            assertThat(jdbc.queryForObject("SELECT status FROM routes WHERE id = ?", String.class, route))
                    .isEqualTo("TAMAMLANDI");
            assertThat(jdbc.queryForObject("SELECT status FROM delivery_plans WHERE id = ?", String.class, plan))
                    .isEqualTo("TAMAMLANDI");
        }

        @Test
        @DisplayName("optimizer çalışmıyorsa erteleme yine geçerlidir; kalan sıra dokunulmadan kalır")
        void shouldKeepPostponementWhenOptimizerIsDown() throws Exception {
            when(optimizer.optimize(any()))
                    .thenThrow(new ApiException(ErrorCode.OPTIMIZER_UNAVAILABLE, "kapalı"));

            postpone(s2, today.plusDays(1), "alici@test")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ERTELENDI"));

            assertThat(stopStatus(stop2)).isEqualTo("ERTELENDI");
            assertThat(List.of(seq(stop3), seq(stop4))).containsExactly(3, 4);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM reroute_events", Integer.class)).isZero();
        }
    }

    @Test
    @DisplayName("Rota henüz yola çıkmadıysa depodan başlanır (startNodeId boş)")
    void shouldStartFromDepotBeforeFirstStop() throws Exception {
        jdbc.update("UPDATE route_stops SET status = 'BEKLEMEDE', actual_arrival = NULL WHERE id = ?", stop1);
        when(optimizer.optimize(any())).thenReturn(answer(List.of(s1, s3, s4), List.of()));

        postpone(s2, today.plusDays(1), "alici@test").andExpect(status().isOk());

        ArgumentCaptor<OptimizeRequest> sent = ArgumentCaptor.forClass(OptimizeRequest.class);
        verify(optimizer).optimize(sent.capture());
        assertThat(sent.getValue().startNodeId()).isNull();
        assertThat(sent.getValue().stops()).extracting(s -> s.id()).containsExactly(s1, s3, s4);
    }

    @Nested
    @DisplayName("Kurallar")
    class Rules {

        @Test
        @DisplayName("teslim edilmiş kargo ertelenemez (409)")
        void shouldRejectDeliveredShipment() throws Exception {
            postpone(s1, today.plusDays(1), "alici@test")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("SHIPMENT_NOT_POSTPONABLE"));
        }

        @Test
        @DisplayName("bugüne, geçmişe ya da 14 günden ileriye ertelenemez (400)")
        void shouldRejectDatesOutsideWindow() throws Exception {
            postpone(s2, today, "alici@test").andExpect(status().isBadRequest());
            postpone(s2, today.minusDays(1), "alici@test").andExpect(status().isBadRequest());
            postpone(s2, today.plusDays(15), "alici@test").andExpect(status().isBadRequest());
            mvc.perform(post("/api/v1/me/shipments/" + s2 + "/postpone").header("Authorization", bearer("alici@test"))
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest());

            assertThat(stopStatus(stop2)).isEqualTo("BEKLEMEDE");
            verify(optimizer, never()).optimize(any());
        }

        @Test
        @DisplayName("başkasının kargosu ertelenemez (404); durak rotada kalır")
        void shouldHideOthersShipment() throws Exception {
            postpone(s2, today.plusDays(1), "baska@test").andExpect(status().isNotFound());

            assertThat(stopStatus(stop2)).isEqualTo("BEKLEMEDE");
            verify(optimizer, never()).optimize(any());
        }

        @Test
        @DisplayName("sevk edilmemiş kargo ertelenir, rota hesabı yapılmaz")
        void shouldPostponeUnplannedShipmentWithoutReroute() throws Exception {
            long recipient = jdbc.queryForObject("SELECT recipient_id FROM shipments WHERE id = ?", Long.class, s2);
            long address = jdbc.queryForObject("SELECT address_id FROM shipments WHERE id = ?", Long.class, s2);
            long waiting = shipment("BND-2026-08009", recipient, address, "BEKLEMEDE", 0);

            postpone(waiting, today.plusDays(3), "alici@test")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ERTELENDI"));

            verify(optimizer, never()).optimize(any());
        }
    }

    @Test
    @DisplayName("Günlük limit dolduysa durak çıkar ama sıra değişmez; kurye yine bilgilendirilir")
    void shouldKeepOrderOnceDailyLimitIsReached() throws Exception {
        for (int i = 0; i < 10; i++) {
            jdbc.update("""
                    INSERT INTO reroute_events (route_id, trigger_type, start_node_id, open_stops_before, open_stops_after)
                    VALUES (?, 'ERTELEME', 1, 3, 3)
                    """, route);
        }

        postpone(s2, today.plusDays(1), "alici@test").andExpect(status().isOk());

        verify(optimizer, never()).optimize(any());
        assertThat(stopStatus(stop2)).isEqualTo("ERTELENDI");
        assertThat(List.of(seq(stop3), seq(stop4))).containsExactly(3, 4);
        assertThat(jdbc.queryForObject("""
                SELECT payload->>'orderKept' FROM notifications WHERE user_id = ? AND type = 'ROTA_DEGISTI'
                """, String.class, courierUser)).isEqualTo("true");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reroute_events WHERE route_id = ? AND order_kept",
                Integer.class, route)).isEqualTo(1);
        changes("kurye@test", null)
                .andExpect(jsonPath("$.changes[10].orderKept").value(true))
                .andExpect(jsonPath("$.changes[10].removed[0].stopId").value(stop2))
                .andExpect(jsonPath("$.changes[10].reordered").isEmpty());
    }

    @Test
    @DisplayName("Sırayı korumuş değişiklikler günlük limite sayılmaz")
    void shouldNotCountKeptOrderTowardsLimit() throws Exception {
        for (int i = 0; i < 10; i++) {
            jdbc.update("""
                    INSERT INTO reroute_events (route_id, trigger_type, start_node_id, open_stops_before,
                                                open_stops_after, order_kept)
                    VALUES (?, 'ERTELEME', 1, 3, 3, true)
                    """, route);
        }
        when(optimizer.optimize(any())).thenReturn(answer(List.of(s3, s4), List.of()));

        postpone(s2, today.plusDays(1), "alici@test").andExpect(status().isOk());

        verify(optimizer).optimize(any());
    }

    @Nested
    @DisplayName("Kuryenin rota değişti ekranı (K5)")
    class CourierChanges {

        @Test
        @DisplayName("alıcı ertelemesi: çıkan durak sebebiyle, numarası değişen durak eski ve yeni yeriyle")
        void shouldListRemovedAndReorderedStops() throws Exception {
            when(optimizer.optimize(any())).thenReturn(answer(List.of(s4, s3), List.of()));
            postpone(s2, today.plusDays(2), "alici@test").andExpect(status().isOk());

            // s4 4. iken 2. oldu; s3 s4'ün arkasına düştü ama numarası 3 kaldı, kuryeye "değişmedi".
            changes("kurye@test", null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.routeId").value(route))
                    .andExpect(jsonPath("$.recomputing").value(false))
                    .andExpect(jsonPath("$.changes.length()").value(1))
                    .andExpect(jsonPath("$.changes[0].trigger").value("ERTELEME"))
                    .andExpect(jsonPath("$.changes[0].orderKept").value(false))
                    .andExpect(jsonPath("$.changes[0].openStops").value(2))
                    .andExpect(jsonPath("$.changes[0].removed.length()").value(1))
                    .andExpect(jsonPath("$.changes[0].removed[0].stopId").value(stop2))
                    .andExpect(jsonPath("$.changes[0].removed[0].orderNumber").value("BND-2026-08002"))
                    .andExpect(jsonPath("$.changes[0].removed[0].reason").value("ALICI_ERTELEDI"))
                    .andExpect(jsonPath("$.changes[0].moved").isEmpty())
                    .andExpect(jsonPath("$.changes[0].reordered.length()").value(1))
                    .andExpect(jsonPath("$.changes[0].reordered[0].stopId").value(stop4))
                    .andExpect(jsonPath("$.changes[0].reordered[0].fromSeq").value(4))
                    .andExpect(jsonPath("$.changes[0].reordered[0].toSeq").value(2));
        }

        @Test
        @DisplayName("sıra aynı kalırsa yalnızca çıkan durak listelenir")
        void shouldNotReportShiftedNumbersAsReordering() throws Exception {
            when(optimizer.optimize(any())).thenReturn(answer(List.of(s3, s4), List.of()));
            postpone(s2, today.plusDays(1), "alici@test").andExpect(status().isOk());

            changes("kurye@test", null)
                    .andExpect(jsonPath("$.changes[0].removed.length()").value(1))
                    .andExpect(jsonPath("$.changes[0].reordered").isEmpty());
        }

        @Test
        @DisplayName("güne sığmayan durak otomatik ertelenir, aynı değişiklikte kendi sebebiyle görünür")
        void shouldListAutoPostponedStopWithSolverReason() throws Exception {
            when(optimizer.optimize(any()))
                    .thenThrow(new ApiException(ErrorCode.NO_FEASIBLE_SOLUTION, "sığmıyor"))
                    .thenReturn(answer(List.of(s3), List.of()));
            postpone(s2, today.plusDays(1), "alici@test").andExpect(status().isOk());

            changes("kurye@test", null)
                    .andExpect(jsonPath("$.changes[0].removed.length()").value(2))
                    .andExpect(jsonPath("$.changes[0].removed[?(@.stopId == " + stop2 + ")].reason")
                            .value("ALICI_ERTELEDI"))
                    .andExpect(jsonPath("$.changes[0].removed[?(@.stopId == " + stop4 + ")].reason")
                            .value("EXCEEDS_WORKDAY"));
        }

        @Test
        @DisplayName("önceki değişiklikte bildirilen durak sonrakinde tekrar çıktı diye görünmez")
        void shouldReportEachRemovalOnce() throws Exception {
            when(optimizer.optimize(any()))
                    .thenReturn(answer(List.of(s3, s4), List.of()))
                    .thenReturn(answer(List.of(s4), List.of()));
            postpone(s2, today.plusDays(1), "alici@test").andExpect(status().isOk());
            postpone(s3, today.plusDays(1), "alici@test").andExpect(status().isOk());

            changes("kurye@test", null)
                    .andExpect(jsonPath("$.changes.length()").value(2))
                    .andExpect(jsonPath("$.changes[1].removed.length()").value(1))
                    .andExpect(jsonPath("$.changes[1].removed[0].stopId").value(stop3));
        }

        @Test
        @DisplayName("since verilirse yalnızca ondan sonraki değişiklikler gelir")
        void shouldFilterBySince() throws Exception {
            when(optimizer.optimize(any())).thenReturn(answer(List.of(s3, s4), List.of()));
            postpone(s2, today.plusDays(1), "alici@test").andExpect(status().isOk());
            Instant created = jdbc.queryForObject("SELECT created_at FROM reroute_events WHERE route_id = ?",
                    Timestamp.class, route).toInstant();

            changes("kurye@test", created.minusSeconds(1)).andExpect(jsonPath("$.changes.length()").value(1));
            changes("kurye@test", created).andExpect(jsonPath("$.changes").isEmpty());
        }

        @Test
        @DisplayName("rotası olmayan kurye 404 alır; firma kuryeyi belirterek okur")
        void shouldScopeToOwnRoute() throws Exception {
            courier(user("baska-kurye@test", "COURIER"));

            changes("baska-kurye@test", null).andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/courier/route/changes").param("courierId", String.valueOf(courierOf(route)))
                            .header("Authorization", bearer("firma@test")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.routeId").value(route));
        }
    }

    private ResultActions changes(String who, Instant since) throws Exception {
        var req = get("/api/v1/courier/route/changes").header("Authorization", bearer(who));
        if (since != null) {
            req = req.param("since", since.toString());
        }
        return mvc.perform(req);
    }

    private long courierOf(long routeId) {
        return jdbc.queryForObject("SELECT courier_id FROM routes WHERE id = ?", Long.class, routeId);
    }

    @Nested
    @DisplayName("Dağıtım sırasında adres değişikliği")
    class AddressChange {

        private long moved;

        /** S3 kendi adresinde (düğüm 103); alıcı onu düğüm 555'e taşıdı, firma kararı bekleniyor. */
        @BeforeEach
        void moveS3() {
            long recipient = jdbc.queryForObject("SELECT recipient_id FROM shipments WHERE id = ?", Long.class, s3);
            moved = jdbc.queryForObject("""
                    INSERT INTO addresses (recipient_id, address_text, location, verification_status, snapped_node_id)
                    VALUES (?, 'Yeni Sokak 9', ST_SetSRID(ST_MakePoint(27.98, 40.36), 4326), 'DOGRULAMA_BEKLIYOR', 555)
                    RETURNING id
                    """, Long.class, recipient);
            jdbc.update("UPDATE shipments SET address_id = ? WHERE id = ?", moved, s3);
        }

        @Test
        @DisplayName("onaylanınca durak yeni düğüme taşınır ve rota yeniden hesaplanır")
        void shouldRerouteWhenMovedAddressIsVerified() throws Exception {
            when(optimizer.optimize(any())).thenReturn(answer(List.of(s2, s3, s4), List.of()));

            mvc.perform(post("/api/v1/addresses/" + moved + "/verify").header("Authorization", bearer("firma@test")))
                    .andExpect(status().isOk());

            assertThat(jdbc.queryForObject("SELECT node_id FROM route_stops WHERE id = ?", Long.class, stop3))
                    .isEqualTo(555L);
            // Yeniden rotalama + taşınan durağın ek maliyet ölçümü (durak olmadan kalan tur).
            ArgumentCaptor<OptimizeRequest> sent = ArgumentCaptor.forClass(OptimizeRequest.class);
            verify(optimizer, times(2)).optimize(sent.capture());
            assertThat(sent.getAllValues().get(0).stops()).filteredOn(st -> st.id() == s3)
                    .singleElement().extracting(st -> st.nodeId()).isEqualTo(555L);
            assertThat(suggestionRows()).isZero();
            assertThat(jdbc.queryForObject("SELECT trigger_type FROM reroute_events WHERE route_id = ?",
                    String.class, route)).isEqualTo("ADRES_DEGISIKLIGI");
            changes("kurye@test", null)
                    .andExpect(jsonPath("$.changes[0].moved.length()").value(1))
                    .andExpect(jsonPath("$.changes[0].moved[0].stopId").value(stop3))
                    .andExpect(jsonPath("$.changes[0].removed").isEmpty());
        }

        @Test
        @DisplayName("reddedilince durak rotadan çıkar, kargo adres düzelene kadar havuza döner")
        void shouldDropStopWhenMovedAddressIsRejected() throws Exception {
            when(optimizer.optimize(any())).thenReturn(answer(List.of(s2, s4), List.of()));

            mvc.perform(post("/api/v1/addresses/" + moved + "/reject").header("Authorization", bearer("firma@test"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Bina bulunamadı\"}"))
                    .andExpect(status().isOk());

            assertThat(stopStatus(stop3)).isEqualTo("ERTELENDI");
            assertThat(jdbc.queryForObject("SELECT status FROM shipments WHERE id = ?", String.class, s3))
                    .isEqualTo("BEKLEMEDE");
            ArgumentCaptor<OptimizeRequest> sent = ArgumentCaptor.forClass(OptimizeRequest.class);
            verify(optimizer).optimize(sent.capture());
            assertThat(sent.getValue().stops()).extracting(st -> st.id()).containsExactly(s2, s4);
            changes("kurye@test", null)
                    .andExpect(jsonPath("$.changes[0].removed[0].stopId").value(stop3))
                    .andExpect(jsonPath("$.changes[0].removed[0].reason").value("ADRES_REDDEDILDI"))
                    .andExpect(jsonPath("$.changes[0].moved").isEmpty());
        }

        @Test
        @DisplayName("aynı düğüme düşen onay rotayı yeniden hesaplatmaz")
        void shouldNotRerouteWhenNodeIsUnchanged() throws Exception {
            jdbc.update("UPDATE addresses SET snapped_node_id = 103 WHERE id = ?", moved);

            mvc.perform(post("/api/v1/addresses/" + moved + "/verify").header("Authorization", bearer("firma@test")))
                    .andExpect(status().isOk());

            verify(optimizer, never()).optimize(any());
        }

        @Test
        @DisplayName("kargo yoldayken alıcı adresi taşıyamaz (409 ADDRESS_LOCKED)")
        void shouldLockAddressWhileParcelIsOnTheRoad() throws Exception {
            mvc.perform(put("/api/v1/me/addresses/" + moved).header("Authorization", bearer("alici@test"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"addressText\":\"Başka yer\",\"location\":{\"lat\":40.35,\"lon\":27.97}}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ADDRESS_LOCKED"));

            assertThat(jdbc.queryForObject("SELECT address_text FROM addresses WHERE id = ?", String.class, moved))
                    .isEqualTo("Yeni Sokak 9");
        }

        @Nested
        @DisplayName("Maliyet artışı eşiği → firmaya erteleme önerisi")
        class CostThreshold {

            /** S3 yeni adresiyle kalan turu 100 TL'den 125 TL'ye çıkarır: %25 > %15. */
            private void verifyExpensiveMove() throws Exception {
                mvc.perform(post("/api/v1/addresses/" + moved + "/verify").header("Authorization", bearer("firma@test")))
                        .andExpect(status().isOk());
            }

            private ResultActions decide(long suggestion, String action) throws Exception {
                return mvc.perform(post("/api/v1/reroute/suggestions/" + suggestion + "/" + action)
                        .header("Authorization", bearer("firma@test")));
            }

            private ResultActions list() throws Exception {
                return mvc.perform(get("/api/v1/reroute/suggestions").header("Authorization", bearer("firma@test")));
            }

            private long openSuggestion() {
                return jdbc.queryForObject("SELECT id FROM postpone_suggestions WHERE status = 'ACIK'", Long.class);
            }

            private String suggestionStatus(long id) {
                return jdbc.queryForObject("SELECT status FROM postpone_suggestions WHERE id = ?", String.class, id);
            }

            @Test
            @DisplayName("taşınan durak kalan turu eşikten fazla pahalılaştırırsa öneri açılır")
            void shouldSuggestWhenMoveRaisesCostAboveThreshold() throws Exception {
                when(optimizer.optimize(any()))
                        .thenReturn(priced(List.of(s2, s3, s4), 125.0))
                        .thenReturn(priced(List.of(s2, s4), 100.0));

                verifyExpensiveMove();

                ArgumentCaptor<OptimizeRequest> sent = ArgumentCaptor.forClass(OptimizeRequest.class);
                verify(optimizer, times(2)).optimize(sent.capture());
                assertThat(sent.getAllValues().get(1).stops()).extracting(st -> st.id()).containsExactly(s2, s4);
                list().andExpect(status().isOk())
                        .andExpect(jsonPath("$.length()").value(1))
                        .andExpect(jsonPath("$[0].routeId").value(route))
                        .andExpect(jsonPath("$[0].stopId").value(stop3))
                        .andExpect(jsonPath("$[0].shipmentId").value(s3))
                        .andExpect(jsonPath("$[0].orderNumber").value("BND-2026-08003"))
                        .andExpect(jsonPath("$[0].addressText").value("Yeni Sokak 9"))
                        .andExpect(jsonPath("$[0].vehiclePlate").value("10 R 1"))
                        .andExpect(jsonPath("$[0].costWithoutTry").value(100.0))
                        .andExpect(jsonPath("$[0].costWithTry").value(125.0))
                        .andExpect(jsonPath("$[0].extraCostTry").value(25.0))
                        .andExpect(jsonPath("$[0].extraRatio").value(0.25))
                        .andExpect(jsonPath("$[0].status").value("ACIK"));
                // Öneri yalnızca öneri: durak rotada, yeni adrese taşınmış olarak kalır.
                assertThat(stopStatus(stop3)).isEqualTo("BEKLEMEDE");
            }

            @Test
            @DisplayName("tam eşikte (%15) öneri açılmaz")
            void shouldNotSuggestAtThreshold() throws Exception {
                when(optimizer.optimize(any()))
                        .thenReturn(priced(List.of(s2, s3, s4), 115.0))
                        .thenReturn(priced(List.of(s2, s4), 100.0));

                verifyExpensiveMove();

                assertThat(suggestionRows()).isZero();
                list().andExpect(jsonPath("$").isEmpty());
            }

            @Test
            @DisplayName("ek maliyet ölçülemezse yeniden rotalama yine yazılır, öneri açılmaz")
            void shouldKeepRerouteWhenMeasurementFails() throws Exception {
                when(optimizer.optimize(any()))
                        .thenReturn(priced(List.of(s2, s3, s4), 125.0))
                        .thenThrow(new ApiException(ErrorCode.OPTIMIZER_UNAVAILABLE, "kapalı"));

                verifyExpensiveMove();

                assertThat(jdbc.queryForObject("SELECT count(*) FROM reroute_events WHERE route_id = ?",
                        Integer.class, route)).isEqualTo(1);
                assertThat(suggestionRows()).isZero();
            }

            @Test
            @DisplayName("firma yarına erteler: durak çıkar, alıcıya bildirilir, kurye sebebi görür")
            void shouldPostponeOnCompanyDecision() throws Exception {
                when(optimizer.optimize(any()))
                        .thenReturn(priced(List.of(s2, s3, s4), 125.0))
                        .thenReturn(priced(List.of(s2, s4), 100.0))
                        .thenReturn(priced(List.of(s2, s4), 100.0));
                verifyExpensiveMove();
                long id = openSuggestion();

                decide(id, "postpone").andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("ERTELENDI"));

                assertThat(stopStatus(stop3)).isEqualTo("ERTELENDI");
                assertThat(jdbc.queryForMap("SELECT status, postponed_to FROM shipments WHERE id = ?", s3))
                        .containsEntry("status", "ERTELENDI")
                        .containsEntry("postponed_to", Date.valueOf(today.plusDays(1)));
                assertThat(jdbc.queryForObject("""
                        SELECT payload->>'reason' FROM notifications
                        WHERE user_id = ? AND type = 'ERTELEME' AND shipment_id = ?
                        """, String.class, recipientUser, s3)).isEqualTo("FIRMA_ERTELEDI");
                verify(optimizer, times(3)).optimize(any());
                changes("kurye@test", null)
                        .andExpect(jsonPath("$.changes.length()").value(2))
                        .andExpect(jsonPath("$.changes[1].removed[0].stopId").value(stop3))
                        .andExpect(jsonPath("$.changes[1].removed[0].reason").value("FIRMA_ERTELEDI"));
                list().andExpect(jsonPath("$").isEmpty());

                decide(id, "postpone").andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("SUGGESTION_CLOSED"));
            }

            @Test
            @DisplayName("firma bugün kalsın der: öneri kapanır, rota değişmez")
            void shouldKeepOnCompanyDecision() throws Exception {
                when(optimizer.optimize(any()))
                        .thenReturn(priced(List.of(s2, s3, s4), 125.0))
                        .thenReturn(priced(List.of(s2, s4), 100.0));
                verifyExpensiveMove();
                long id = openSuggestion();

                decide(id, "keep").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("KALDI"));

                assertThat(stopStatus(stop3)).isEqualTo("BEKLEMEDE");
                verify(optimizer, times(2)).optimize(any());
                list().andExpect(jsonPath("$").isEmpty());
                decide(id, "postpone").andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("SUGGESTION_CLOSED"));
            }

            @Test
            @DisplayName("kurye durağı kapattıysa öneri listelenmez, ertelenemez")
            void shouldNotPostponeClosedStop() throws Exception {
                when(optimizer.optimize(any()))
                        .thenReturn(priced(List.of(s2, s3, s4), 125.0))
                        .thenReturn(priced(List.of(s2, s4), 100.0));
                verifyExpensiveMove();
                long id = openSuggestion();
                mvc.perform(post("/api/v1/courier/stops/" + stop3 + "/deliver").header("Authorization", bearer("kurye@test")))
                        .andExpect(status().isOk());

                list().andExpect(jsonPath("$").isEmpty());
                decide(id, "postpone").andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("STOP_ALREADY_CLOSED"));
                assertThat(suggestionStatus(id)).isEqualTo("ACIK");
            }

            @Test
            @DisplayName("durak başka sebeple rotadan çıkınca öneri geçersiz olur")
            void shouldInvalidateWhenStopLeavesRoute() throws Exception {
                when(optimizer.optimize(any()))
                        .thenReturn(priced(List.of(s2, s3, s4), 125.0))
                        .thenReturn(priced(List.of(s2, s4), 100.0))
                        .thenReturn(priced(List.of(s2, s4), 100.0));
                verifyExpensiveMove();
                long id = openSuggestion();

                postpone(s3, today.plusDays(2), "alici@test").andExpect(status().isOk());

                assertThat(suggestionStatus(id)).isEqualTo("GECERSIZ");
                decide(id, "keep").andExpect(status().isConflict());
            }
        }
    }
}
