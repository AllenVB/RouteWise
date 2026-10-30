package com.routewise.optimization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import com.routewise.address.Address;
import com.routewise.address.AddressRepository;
import com.routewise.common.error.ApiException;
import com.routewise.common.error.ErrorCode;
import com.routewise.common.geo.Points;
import com.routewise.cost.CostParameters;
import com.routewise.cost.CostParametersRepository;
import com.routewise.depot.Depot;
import com.routewise.depot.DepotService;
import com.routewise.optimization.RunDtos.OptimizeResponse;
import com.routewise.optimization.client.OptimizerClient;
import com.routewise.optimization.client.OptimizerDtos.OptimizeRequest;
import com.routewise.optimization.client.OptimizerDtos.OptimizeResult;
import com.routewise.optimization.client.OptimizerDtos.RouteOut;
import com.routewise.optimization.client.OptimizerDtos.SolutionOut;
import com.routewise.optimization.client.OptimizerDtos.StopOut;
import com.routewise.optimization.client.OptimizerDtos.UnassignedOut;
import com.routewise.plan.DeliveryPlan;
import com.routewise.plan.DeliveryPlanRepository;
import com.routewise.routing.matrix.DistanceMatrix;
import com.routewise.routing.matrix.DistanceMatrixProvider;
import com.routewise.shipment.Shipment;
import com.routewise.shipment.ShipmentRepository;
import com.routewise.vehicle.Vehicle;
import com.routewise.vehicle.VehicleRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class OptimizationServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 30);
    private static final long DEPOT_NODE = 9000L;

    private DeliveryPlanRepository plans;
    private DepotService depots;
    private VehicleRepository vehicles;
    private ShipmentRepository shipments;
    private AddressRepository addresses;
    private CostParametersRepository costs;
    private DistanceMatrixProvider matrices;
    private OptimizerClient optimizer;
    private OptimizationRunRepository runs;
    private PlannedRouteRepository routes;
    private RouteStopRepository stops;
    private OptimizationService service;

    private DeliveryPlan plan;
    private final AtomicLong ids = new AtomicLong(500);

    @BeforeEach
    void setUp() {
        plans = mock(DeliveryPlanRepository.class);
        depots = mock(DepotService.class);
        vehicles = mock(VehicleRepository.class);
        shipments = mock(ShipmentRepository.class);
        addresses = mock(AddressRepository.class);
        costs = mock(CostParametersRepository.class);
        when(costs.findFirstByEffectiveFromLessThanEqualOrderByEffectiveFromDesc(DAY)).thenReturn(Optional.of(
                new CostParameters(new BigDecimal("44.00"), new BigDecimal("150.00"), new BigDecimal("2.50"), DAY)));
        matrices = mock(DistanceMatrixProvider.class);
        optimizer = mock(OptimizerClient.class);
        runs = mock(OptimizationRunRepository.class);
        routes = mock(PlannedRouteRepository.class);
        stops = mock(RouteStopRepository.class);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(inv ->
                inv.<TransactionCallback<?>>getArgument(0).doInTransaction(null));

        service = new OptimizationService(plans, depots, vehicles, shipments, addresses, costs,
                matrices, optimizer, runs, routes, stops, tx);

        plan = new DeliveryPlan(1L, DAY, 1L);
        ReflectionTestUtils.setField(plan, "id", 88L);
        when(plans.findById(88L)).thenReturn(Optional.of(plan));
        when(plans.lockById(88L)).thenReturn(Optional.of(plan));

        Depot depot = mock(Depot.class);
        when(depot.getId()).thenReturn(1L);
        when(depot.getSnappedNodeId()).thenReturn(DEPOT_NODE);
        when(depots.primary()).thenReturn(depot);

        when(vehicles.findByDepotIdAndActiveTrueOrderByIdAsc(1L)).thenReturn(List.of(
                new Vehicle(1L, 1L, "10 ABC 001", new BigDecimal("1200")),
                new Vehicle(2L, 1L, "10 ABC 002", new BigDecimal("1500")),
                new Vehicle(3L, 1L, "10 ABC 003", new BigDecimal("800"))));

        when(matrices.forNodes(anyList())).thenReturn(
                new DistanceMatrix("h", List.of(1L), new Double[][] {{0.0}}, new Double[][] {{0.0}}, 0));

        when(runs.save(any(OptimizationRun.class))).thenAnswer(inv -> withId(inv.getArgument(0)));
        when(routes.save(any(PlannedRoute.class))).thenAnswer(inv -> withId(inv.getArgument(0)));
    }

    private <T> T withId(T entity) {
        ReflectionTestUtils.setField(entity, "id", ids.incrementAndGet());
        return entity;
    }

    private static Shipment shipment(long id, long addressId, String weight, int serviceMin) {
        Shipment s = new Shipment("BND-2026-%05d".formatted(id), 12L, addressId,
                new BigDecimal(weight), (short) 0, serviceMin);
        ReflectionTestUtils.setField(s, "id", id);
        return s;
    }

    private static Address address(long id, long node, boolean verified) {
        Address a = new Address(12L, "Adres " + id, null, null, Points.of(40.35, 27.97), node, BigDecimal.ONE);
        ReflectionTestUtils.setField(a, "id", id);
        if (verified) {
            a.verify(1L);
        }
        return a;
    }

    private static SolutionOut solution(String algorithm, double distance, long... shipmentIds) {
        List<StopOut> out = new java.util.ArrayList<>();
        for (int i = 0; i < shipmentIds.length; i++) {
            out.add(new StopOut(shipmentIds[i], 100 + shipmentIds[i], i + 1, 250.0, 25.0));
        }
        // Maliyet = mesafe / 10: tasarruf yüzdeleri iki ölçüde de aynı çıksın.
        return new SolutionOut(algorithm, List.of(new RouteOut(2L, out, 300.0, 30.0, distance,
                100.0, 600.0, 700.0, 7.5, distance / 10_000, distance / 10)),
                distance, 700.0, 1, 0.4, distance / 10_000, distance / 10, 3.0, distance / 10 + 3.0);
    }

    /** Two verified shipments (1, 2) and one whose address went back to the queue (3). */
    private void givenDay() {
        when(shipments.findPlannableOn(DAY)).thenReturn(List.of(
                shipment(1, 31, "2.50", 5), shipment(2, 32, "5.00", 10), shipment(3, 33, "1.00", 5)));
        when(addresses.findAllById(anyList())).thenReturn(List.of(
                address(31, 101, true), address(32, 102, true), address(33, 103, false)));
    }

    private void givenOptimizerReturns(SolutionOut result, SolutionOut baseline, UnassignedOut... unassigned) {
        when(optimizer.optimize(any())).thenReturn(new OptimizeResult(42L, result, baseline, List.of(unassigned)));
    }

    @Nested
    @DisplayName("Optimizere giden istek")
    class Request {

        @Test
        @DisplayName("Doğrulanmamış adresli kargo optimizere hiç gitmez, sebebiyle raporlanır")
        void unverifiedAddressNeverReachesOptimizer() {
            givenDay();
            givenOptimizerReturns(solution("GREEDY_NN", 800, 2, 1), solution("NAIVE", 1000, 1, 2));

            OptimizeResponse r = service.optimize(88L, null);

            var sent = ArgumentCaptor.forClass(OptimizeRequest.class);
            verify(optimizer).optimize(sent.capture());
            assertThat(sent.getValue().stops()).extracting(s -> s.id()).containsExactly(1L, 2L);
            assertThat(r.excluded()).singleElement().satisfies(e -> {
                assertThat(e.shipmentId()).isEqualTo(3L);
                assertThat(e.reason()).isEqualTo("ADDRESS_NOT_VERIFIED");
            });
        }

        @Test
        @DisplayName("Onaylı ama yola bağlanmamış adres NO_ROAD_NODE ile ayrılır — 'onaysız' denmez")
        void verifiedButUnsnappedHasItsOwnReason() {
            Address unsnapped = address(34, 0, true);
            ReflectionTestUtils.setField(unsnapped, "snappedNodeId", null);
            when(shipments.findPlannableOn(DAY)).thenReturn(List.of(
                    shipment(1, 31, "2.50", 5), shipment(4, 34, "1.00", 5)));
            when(addresses.findAllById(anyList())).thenReturn(List.of(address(31, 101, true), unsnapped));
            givenOptimizerReturns(solution("GREEDY_NN", 500, 1), solution("NAIVE", 500, 1));

            OptimizeResponse r = service.optimize(88L, null);

            assertThat(r.excluded()).extracting(e -> e.shipmentId() + ":" + e.reason())
                    .containsExactly("4:NO_ROAD_NODE");
        }

        @Test
        @DisplayName("Duraklar oluşturulma sırasıyla, düğüm ve saniye cinsinden servis süresiyle gider")
        void stopsKeepCreationOrderAndUnits() {
            givenDay();
            givenOptimizerReturns(solution("GREEDY_NN", 800, 2, 1), solution("NAIVE", 1000, 1, 2));

            service.optimize(88L, null);

            var sent = ArgumentCaptor.forClass(OptimizeRequest.class);
            verify(optimizer).optimize(sent.capture());
            OptimizeRequest req = sent.getValue();
            assertThat(req.algorithm()).isEqualTo("ROUTEWISE");
            assertThat(req.depotNodeId()).isEqualTo(DEPOT_NODE);
            assertThat(req.stops()).extracting(s -> s.nodeId()).containsExactly(101L, 102L);
            assertThat(req.stops()).extracting(s -> s.serviceTimeS()).containsExactly(300.0, 600.0);
            assertThat(req.stops()).extracting(s -> s.demandKg()).containsExactly(2.5, 5.0);
            verify(matrices).forNodes(List.of(DEPOT_NODE, 101L, 102L));
        }

        @Test
        @DisplayName("Tüm aktif filo yakıt tüketimi ve sabit gideriyle, plan gününün fiyatlarıyla gider")
        void sendsWholeFleetWithCosts() {
            givenDay();
            givenOptimizerReturns(solution("ROUTEWISE", 800, 2, 1), solution("NAIVE", 1000, 1, 2));

            service.optimize(88L, null);

            var sent = ArgumentCaptor.forClass(OptimizeRequest.class);
            verify(optimizer).optimize(sent.capture());
            assertThat(sent.getValue().vehicles()).extracting(v -> v.capacityKg())
                    .containsExactly(1200.0, 1500.0, 800.0);
            assertThat(sent.getValue().vehicles()).allSatisfy(v -> assertThat(v.fuelLPer100km()).isEqualTo(9.5));
            assertThat(sent.getValue().costParams().priorityPenalty()).isEqualTo(2.5);
            assertThat(sent.getValue().costParams().fuelPricePerL()).isEqualTo(44.0);
        }

        @Test
        @DisplayName("Araç belirtilirse yalnızca o araçla planlanır (tek araç senaryosu)")
        void singleVehicleScenario() {
            givenDay();
            givenOptimizerReturns(solution("ROUTEWISE", 800, 2, 1), solution("NAIVE", 1000, 1, 2));

            service.optimize(88L, req(null, 3L));

            var sent = ArgumentCaptor.forClass(OptimizeRequest.class);
            verify(optimizer).optimize(sent.capture());
            assertThat(sent.getValue().vehicles()).extracting(v -> v.id()).containsExactly(3L);
        }

        @Test
        @DisplayName("Sıcak başlangıç aynı planın koşusundan, rota sırasıyla gider")
        void warmStartFromRunOfSamePlan() {
            givenDay();
            givenOptimizerReturns(solution("ROUTEWISE", 800, 2, 1), solution("NAIVE", 1000, 1, 2));
            OptimizationRun previous = withId(new OptimizationRun(88L, Algorithm.ROUTEWISE, true, false,
                    BigDecimal.TEN, 1, (short) 1, 1));
            PlannedRoute route = withId(new PlannedRoute(previous.getId(), 2L, BigDecimal.TEN, 1, BigDecimal.ONE));
            when(runs.findById(previous.getId())).thenReturn(Optional.of(previous));
            when(routes.findByRunIdOrderByIdAsc(previous.getId())).thenReturn(List.of(route));
            when(stops.findByRouteIdInOrderByRouteIdAscSequenceNoAsc(List.of(route.getId()))).thenReturn(List.of(
                    new RouteStop(route.getId(), 2L, 1, 102L, BigDecimal.ONE, 1),
                    new RouteStop(route.getId(), 1L, 2, 101L, BigDecimal.ONE, 1)));

            service.optimize(88L, new RunDtos.OptimizeRequest(Algorithm.ROUTEWISE, null, 7L, previous.getId()));

            var sent = ArgumentCaptor.forClass(OptimizeRequest.class);
            verify(optimizer).optimize(sent.capture());
            assertThat(sent.getValue().randomSeed()).isEqualTo(7L);
            assertThat(sent.getValue().warmStart()).singleElement().satisfies(w -> {
                assertThat(w.vehicleId()).isEqualTo(2L);
                assertThat(w.stopIds()).containsExactly(2L, 1L);
            });
        }
    }

    @Nested
    @DisplayName("Reddedilen durumlar — optimizer çağrılmaz")
    class Rejections {

        @Test
        @DisplayName("Henüz yazılmamış algoritma VALIDATION_FAILED")
        void unavailableAlgorithm() {
            assertThatThrownBy(() -> service.optimize(88L, req(Algorithm.DFS_EXHAUSTIVE, null)))
                    .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            verify(optimizer, never()).optimize(any());
        }

        @Test
        @DisplayName("Sevk edilmiş plan PLAN_ALREADY_DISPATCHED")
        void dispatchedPlan() {
            ReflectionTestUtils.setField(plan, "status", com.routewise.plan.PlanStatus.SEVK_EDILDI);

            assertThatThrownBy(() -> service.optimize(88L, null))
                    .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.PLAN_ALREADY_DISPATCHED);
            verify(optimizer, never()).optimize(any());
        }

        @Test
        @DisplayName("Hiç doğrulanmış kargo yoksa NO_FEASIBLE_SOLUTION, açıklayıcı mesajla")
        void nothingRoutable() {
            when(shipments.findPlannableOn(DAY)).thenReturn(List.of(shipment(3, 33, "1.00", 5)));
            when(addresses.findAllById(anyList())).thenReturn(List.of(address(33, 103, false)));

            assertThatThrownBy(() -> service.optimize(88L, null))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("doğrulanmamış");
            verify(optimizer, never()).optimize(any());
        }

        @Test
        @DisplayName("Bilinmeyen araç 404")
        void unknownVehicle() {
            givenDay();

            assertThatThrownBy(() -> service.optimize(88L, req(null, 77L)))
                    .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("Kalıcılaştırma")
    class Persist {

        @Test
        @DisplayName("Önce eski uygulanan koşu geri çekilir, sonra baseline ve yeni koşu yazılır")
        void withdrawsBeforeWriting() {
            givenDay();
            givenOptimizerReturns(solution("GREEDY_NN", 800, 2, 1), solution("NAIVE", 1000, 1, 2));

            OptimizeResponse r = service.optimize(88L, null);

            var order = inOrder(plans, runs);
            order.verify(plans).lockById(88L);
            order.verify(runs).withdrawApplied(88L);
            order.verify(runs, times(2)).save(any(OptimizationRun.class));

            assertThat(r.isApplied()).isTrue();
            assertThat(r.algorithm()).isEqualTo(Algorithm.ROUTEWISE);
            assertThat(r.randomSeed()).isEqualTo(42L);
            assertThat(r.totalCostTry()).isEqualByComparingTo("80.00");
            assertThat(r.savingsPct()).isEqualByComparingTo("20.0");
            assertThat(r.baseline().algorithm()).isEqualTo(Algorithm.NAIVE);
            assertThat(r.baseline().runId()).isNotEqualTo(r.runId());
            assertThat(r.distanceSavingsPct()).isEqualByComparingTo("20.0");
            assertThat(r.routes()).singleElement().satisfies(rt -> assertThat(rt.stopCount()).isEqualTo(2));
        }

        @Test
        @DisplayName("Durak satırları sıra, düğüm ve bacak metrikleriyle yazılır")
        @SuppressWarnings("unchecked")
        void writesStopRows() {
            givenDay();
            givenOptimizerReturns(solution("GREEDY_NN", 800, 2, 1), solution("NAIVE", 1000, 1, 2));

            service.optimize(88L, null);

            var saved = ArgumentCaptor.forClass(List.class);
            verify(stops, times(2)).saveAll(saved.capture());
            List<RouteStop> applied = (List<RouteStop>) saved.getAllValues().get(1);
            assertThat(applied).extracting(RouteStop::getShipmentId).containsExactly(2L, 1L);
            assertThat(applied).extracting(RouteStop::getSequenceNo).containsExactly(1, 2);
            assertThat(applied).extracting(RouteStop::getNodeId).containsExactly(102L, 101L);
        }

        @Test
        @DisplayName("NAIVE istenirse tek koşu yazılır: hem baseline hem uygulanan")
        void naiveIsOneRow() {
            givenDay();
            SolutionOut naive = solution("NAIVE", 1000, 1, 2);
            givenOptimizerReturns(naive, naive);

            OptimizeResponse r = service.optimize(88L, req(Algorithm.NAIVE, null));

            var saved = ArgumentCaptor.forClass(OptimizationRun.class);
            verify(runs, times(1)).save(saved.capture());
            assertThat(saved.getValue().isApplied()).isTrue();
            assertThat(saved.getValue().isBaseline()).isTrue();
            assertThat(r.baseline().runId()).isEqualTo(r.runId());
            assertThat(r.distanceSavingsPct()).isEqualByComparingTo("0.0");
        }

        @Test
        @DisplayName("Optimizerin dışladığı durak sipariş numarasıyla raporlanır")
        void reportsUnreachable() {
            givenDay();
            givenOptimizerReturns(solution("GREEDY_NN", 500, 1), solution("NAIVE", 500, 1),
                    new UnassignedOut(2L, "UNREACHABLE"));

            OptimizeResponse r = service.optimize(88L, null);

            assertThat(r.excluded()).extracting(e -> e.orderNumber() + ":" + e.reason())
                    .containsExactly("BND-2026-00003:ADDRESS_NOT_VERIFIED", "BND-2026-00002:UNREACHABLE");
        }

        @Test
        @DisplayName("Kayıt anında plan sevk edilmişse koşu yazılmaz")
        void recheckUnderLock() {
            givenDay();
            givenOptimizerReturns(solution("GREEDY_NN", 800, 2, 1), solution("NAIVE", 1000, 1, 2));
            DeliveryPlan dispatched = new DeliveryPlan(1L, DAY, 1L);
            ReflectionTestUtils.setField(dispatched, "status", com.routewise.plan.PlanStatus.SEVK_EDILDI);
            when(plans.lockById(88L)).thenReturn(Optional.of(dispatched));

            assertThatThrownBy(() -> service.optimize(88L, null))
                    .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.PLAN_ALREADY_DISPATCHED);
            verify(runs, never()).save(any());
        }
    }

    private static RunDtos.OptimizeRequest req(Algorithm algorithm, Long vehicleId) {
        return new RunDtos.OptimizeRequest(algorithm, vehicleId, null, null);
    }

    @Nested
    @DisplayName("Faz 3 kuralları")
    class Phase3 {

        @Test
        @DisplayName("Tohum yalnızca ROUTEWISE koşusuna yazılır; baseline'a yazılmaz")
        void seedOnlyOnSeededRun() {
            givenDay();
            givenOptimizerReturns(solution("ROUTEWISE", 800, 2, 1), solution("NAIVE", 1000, 1, 2));

            service.optimize(88L, null);

            var saved = ArgumentCaptor.forClass(OptimizationRun.class);
            verify(runs, times(2)).save(saved.capture());
            assertThat(saved.getAllValues()).extracting(OptimizationRun::getRandomSeed).containsExactly(null, 42L);
            assertThat(saved.getAllValues()).extracting(OptimizationRun::getTotalCostTry)
                    .allSatisfy(c -> assertThat(c).isNotNull());
        }

        @Test
        @DisplayName("Tohum ya da sıcak başlangıç tohumsuz algoritmayla istenirse VALIDATION_FAILED")
        void seedWithUnseededAlgorithm() {
            assertThatThrownBy(() -> service.optimize(88L,
                    new RunDtos.OptimizeRequest(Algorithm.SAVINGS, null, 5L, null)))
                    .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            verify(optimizer, never()).optimize(any());
        }

        @Test
        @DisplayName("Başka planın koşusundan sıcak başlangıç 404")
        void warmStartFromForeignRun() {
            givenDay();
            OptimizationRun foreign = withId(new OptimizationRun(99L, Algorithm.ROUTEWISE, true, false,
                    BigDecimal.TEN, 1, (short) 1, 1));
            when(runs.findById(foreign.getId())).thenReturn(Optional.of(foreign));

            assertThatThrownBy(() -> service.optimize(88L,
                    new RunDtos.OptimizeRequest(Algorithm.ROUTEWISE, null, null, foreign.getId())))
                    .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.NOT_FOUND);
            verify(optimizer, never()).optimize(any());
        }

        @Test
        @DisplayName("Plan günü için maliyet parametresi yoksa açık hata — maliyetsiz sessiz koşu yok")
        void missingCostParameters() {
            givenDay();
            when(costs.findFirstByEffectiveFromLessThanEqualOrderByEffectiveFromDesc(DAY)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.optimize(88L, null))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("maliyet parametresi");
            verify(optimizer, never()).optimize(any());
        }

        @Test
        @DisplayName("Rota satırına yakıt ve TL maliyet yazılır")
        void routeCostsPersisted() {
            givenDay();
            givenOptimizerReturns(solution("ROUTEWISE", 800, 2, 1), solution("NAIVE", 1000, 1, 2));

            service.optimize(88L, null);

            var saved = ArgumentCaptor.forClass(PlannedRoute.class);
            verify(routes, times(2)).save(saved.capture());
            assertThat(saved.getAllValues().get(1).getTotalCostTry()).isEqualByComparingTo("80.00");
            assertThat(saved.getAllValues().get(1).getTotalFuelL()).isEqualByComparingTo("0.080");
        }
    }

    @Test
    @DisplayName("Tasarruf yüzdesi baseline'a göre; baseline sıfırsa tanımsız")
    void savingsPct() {
        assertThat(OptimizationService.savingsPct(new BigDecimal("100040"), new BigDecimal("86350")))
                .isEqualByComparingTo("13.7");
        assertThat(OptimizationService.savingsPct(BigDecimal.ZERO, BigDecimal.ZERO)).isNull();
    }
}
