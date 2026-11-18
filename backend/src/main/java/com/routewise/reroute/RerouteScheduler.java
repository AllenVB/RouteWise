package com.routewise.reroute;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.routewise.common.error.ApiException;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Asks for a reroute without making the caller wait for it (roadmap phase 5, task 5).
 *
 * <p>Two rules:
 * <ul>
 *   <li><b>After commit.</b> The change that triggered it must be visible to the
 *       reroute's own transactions, and a rolled-back change must not reroute.</li>
 *   <li><b>Debounced per route.</b> Ten changes to one route within the window
 *       are one optimization, run on the state after the last of them.</li>
 * </ul>
 *
 * <p>A failed background reroute is logged, not retried: the change itself is
 * committed, and the next change to the route computes it again.
 */
@Component
public class RerouteScheduler {

    private static final Logger log = LoggerFactory.getLogger(RerouteScheduler.class);

    private final RerouteService reroute;
    private final long debounceMs;
    private final ScheduledExecutorService executor;
    private final ConcurrentHashMap<Long, Pending> pending = new ConcurrentHashMap<>();

    @Autowired
    public RerouteScheduler(RerouteService reroute, RerouteProperties props) {
        this(reroute, props, Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "reroute");
            t.setDaemon(true);
            return t;
        }));
    }

    RerouteScheduler(RerouteService reroute, RerouteProperties props, ScheduledExecutorService executor) {
        this.reroute = reroute;
        this.debounceMs = props.debounce().toMillis();
        this.executor = executor;
    }

    /** The latest request wins: its trigger and shipment are what the reroute records. */
    private static final class Pending {
        final RerouteEvent.Trigger trigger;
        final Long shipmentId;
        final int changes;
        volatile ScheduledFuture<?> future;

        Pending(RerouteEvent.Trigger trigger, Long shipmentId, int changes) {
            this.trigger = trigger;
            this.shipmentId = shipmentId;
            this.changes = changes;
        }
    }

    public void request(Long routeId, RerouteEvent.Trigger trigger, Long shipmentId) {
        if (debounceMs <= 0) {
            run(routeId, trigger, shipmentId, 1);
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    enqueue(routeId, trigger, shipmentId);
                }
            });
        } else {
            enqueue(routeId, trigger, shipmentId);
        }
    }

    private void enqueue(Long routeId, RerouteEvent.Trigger trigger, Long shipmentId) {
        pending.compute(routeId, (id, old) -> {
            if (old != null) {
                old.future.cancel(false);
            }
            Pending p = new Pending(trigger, shipmentId, old == null ? 1 : old.changes + 1);
            p.future = executor.schedule(() -> fire(id, p), debounceMs, TimeUnit.MILLISECONDS);
            return p;
        });
    }

    private void fire(Long routeId, Pending p) {
        // Yalnızca hâlâ en son istek buysa; aradaki istek kendi zamanlayıcısıyla koşacak.
        if (pending.remove(routeId, p)) {
            run(routeId, p.trigger, p.shipmentId, p.changes);
        }
    }

    private void run(Long routeId, RerouteEvent.Trigger trigger, Long shipmentId, int changes) {
        try {
            RerouteService.Outcome out = reroute.reroute(routeId, trigger, shipmentId);
            if (changes > 1) {
                log.info("rota {}: {} degisiklik tek yeniden rotalamada birlesti (yapildi: {})",
                        routeId, changes, out.rerouted());
            }
        } catch (ApiException e) {
            // Değişikliğin kendisi kayıtlı; yalnızca kalan sıra ve ETA'lar eski kaldı.
            log.warn("rota {} yeniden hesaplanamadi ({}): {} {}", routeId, trigger, e.code(), e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
