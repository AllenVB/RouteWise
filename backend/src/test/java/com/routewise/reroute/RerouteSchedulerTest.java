package com.routewise.reroute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import com.routewise.common.error.ApiException;
import com.routewise.common.error.ErrorCode;
import com.routewise.reroute.RerouteEvent.Trigger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class RerouteSchedulerTest {

    private static final Duration WINDOW = Duration.ofMillis(150);

    private final RerouteService reroute = mock(RerouteService.class);
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final RerouteScheduler scheduler =
            new RerouteScheduler(reroute, new RerouteProperties(WINDOW, 10, null), executor);

    RerouteSchedulerTest() {
        when(reroute.reroute(anyLong(), any(), any(), any()))
                .thenAnswer(inv -> new RerouteService.Outcome(inv.getArgument(0), true, 1, List.of()));
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("Arka arkaya 10 değişiklik 10 değil 1 optimizasyon koşturur; son değişiklik kaydedilir, hepsi kuryeye gider")
    void shouldCoalesceBurstIntoOneReroute() {
        for (long shipment = 1; shipment <= 10; shipment++) {
            scheduler.request(7L, Trigger.ERTELEME, shipment);
        }

        Set<Long> all = LongStream.rangeClosed(1, 10).boxed().collect(Collectors.toSet());
        verify(reroute, timeout(2_000).times(1)).reroute(7L, Trigger.ERTELEME, 10L, all);
        verify(reroute, after(WINDOW.toMillis() * 3).times(1)).reroute(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("Farklı rotalar birbirini beklemez, her biri kendi koşusunu alır")
    void shouldRerouteEachRouteSeparately() {
        scheduler.request(7L, Trigger.ERTELEME, 1L);
        scheduler.request(8L, Trigger.ADRES_DEGISIKLIGI, 2L);

        verify(reroute, timeout(2_000)).reroute(7L, Trigger.ERTELEME, 1L, Set.of(1L));
        verify(reroute, timeout(2_000)).reroute(8L, Trigger.ADRES_DEGISIKLIGI, 2L, Set.of(2L));
    }

    @Test
    @DisplayName("Transaction içindeki istek commit'ten önce koşmaz; geri alınan değişiklik hiç koşmaz")
    void shouldWaitForCommit() {
        TransactionSynchronizationManager.initSynchronization();
        scheduler.request(7L, Trigger.ERTELEME, 1L);

        verify(reroute, after(WINDOW.toMillis() * 3).never()).reroute(anyLong(), any(), any(), any());

        List<TransactionSynchronization> registered = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        registered.forEach(TransactionSynchronization::afterCommit);

        verify(reroute, timeout(2_000)).reroute(7L, Trigger.ERTELEME, 1L, Set.of(1L));
    }

    @Test
    @DisplayName("Bekleme süresi sıfırsa hemen, çağıranın içinde koşar")
    void shouldRunInlineWithZeroDebounce() {
        RerouteScheduler inline = new RerouteScheduler(reroute, new RerouteProperties(Duration.ZERO, 10, null), executor);

        inline.request(7L, Trigger.ERTELEME, 1L);

        verify(reroute).reroute(7L, Trigger.ERTELEME, 1L, Set.of(1L));
    }

    @Test
    @DisplayName("Arka plandaki hata loglanır, zamanlayıcıyı düşürmez; sonraki istek koşar")
    void shouldSurviveFailedReroute() {
        when(reroute.reroute(eq(7L), any(), any(), any()))
                .thenThrow(new ApiException(ErrorCode.OPTIMIZER_UNAVAILABLE, "kapalı"))
                .thenReturn(new RerouteService.Outcome(7L, true, 1, List.of()));

        scheduler.request(7L, Trigger.ERTELEME, 1L);
        verify(reroute, timeout(2_000)).reroute(7L, Trigger.ERTELEME, 1L, Set.of(1L));
        scheduler.request(7L, Trigger.ERTELEME, 2L);

        verify(reroute, timeout(2_000)).reroute(7L, Trigger.ERTELEME, 2L, Set.of(2L));
        verify(reroute, never()).reroute(eq(8L), any(), any(), any());
    }

    @Test
    @DisplayName("İstek gelince rota 'hesaplanıyor' görünür, koşu bitince görünmez")
    void shouldReportBusyUntilRerouteFinishes() {
        assertThat(scheduler.isBusy(7L)).isFalse();

        scheduler.request(7L, Trigger.ERTELEME, 1L);

        assertThat(scheduler.isBusy(7L)).isTrue();
        assertThat(scheduler.isBusy(8L)).isFalse();
        verify(reroute, timeout(2_000)).reroute(7L, Trigger.ERTELEME, 1L, Set.of(1L));
        await(() -> !scheduler.isBusy(7L));
    }

    @Test
    @DisplayName("Beklenmedik hata da zamanlayıcıyı düşürmez, rota 'hesaplanıyor'da takılı kalmaz")
    void shouldSurviveUnexpectedFailure() {
        when(reroute.reroute(eq(7L), any(), any(), any()))
                .thenThrow(new IllegalStateException("bozuk"))
                .thenReturn(new RerouteService.Outcome(7L, true, 1, List.of()));

        scheduler.request(7L, Trigger.ERTELEME, 1L);
        verify(reroute, timeout(2_000)).reroute(7L, Trigger.ERTELEME, 1L, Set.of(1L));
        await(() -> !scheduler.isBusy(7L));
        scheduler.request(7L, Trigger.ERTELEME, 2L);

        verify(reroute, timeout(2_000)).reroute(7L, Trigger.ERTELEME, 2L, Set.of(2L));
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("koşul 2 sn içinde sağlanmadı").isLessThan(deadline);
            Thread.onSpinWait();
        }
    }
}
