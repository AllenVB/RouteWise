package com.routewise.reroute;

import java.util.LinkedHashSet;
import java.util.Set;
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
 *
 * <p>{@link #isBusy} is the courier screen's progress indicator: a route is busy
 * from the commit of a change until its reroute has finished.
 */
@Component
public class RerouteScheduler {

    private static final Logger log = LoggerFactory.getLogger(RerouteScheduler.class);

    private final RerouteService reroute;
    private final long debounceMs;
    private final ScheduledExecutorService executor;
    private final ConcurrentHashMap<Long, Pending> pending = new ConcurrentHashMap<>();
    private final Set<Long> running = ConcurrentHashMap.newKeySet();

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

    /**
     * The latest request wins for the trigger and the shipment the event records;
     * every folded-in shipment is kept so the courier sees all of them (K5).
     */
    private static final class Pending {
        final RerouteEvent.Trigger trigger;
        final Long shipmentId;
        final Set<Long> causes;
        volatile ScheduledFuture<?> future;

        Pending(RerouteEvent.Trigger trigger, Long shipmentId, Set<Long> causes) {
            this.trigger = trigger;
            this.shipmentId = shipmentId;
            this.causes = causes;
        }
    }

    public void request(Long routeId, RerouteEvent.Trigger trigger, Long shipmentId) {
        if (debounceMs <= 0) {
            run(routeId, trigger, shipmentId, causesOf(null, shipmentId));
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

    /** A change for this route is committed and its reroute has not finished yet. */
    public boolean isBusy(Long routeId) {
        return pending.containsKey(routeId) || running.contains(routeId);
    }

    private void enqueue(Long routeId, RerouteEvent.Trigger trigger, Long shipmentId) {
        pending.compute(routeId, (id, old) -> {
            if (old != null) {
                old.future.cancel(false);
            }
            Pending p = new Pending(trigger, shipmentId, causesOf(old == null ? null : old.causes, shipmentId));
            p.future = executor.schedule(() -> fire(id, p), debounceMs, TimeUnit.MILLISECONDS);
            return p;
        });
    }

    private static Set<Long> causesOf(Set<Long> earlier, Long shipmentId) {
        Set<Long> causes = earlier == null ? new LinkedHashSet<>() : new LinkedHashSet<>(earlier);
        if (shipmentId != null) {
            causes.add(shipmentId);
        }
        return Set.copyOf(causes);
    }

    private void fire(Long routeId, Pending p) {
        // Yalnızca hâlâ en son istek buysa; aradaki istek kendi zamanlayıcısıyla koşacak.
        // Önce "koşuyor" işaretlenir: arada isBusy bir an bile false dönmesin.
        running.add(routeId);
        try {
            if (pending.remove(routeId, p)) {
                run(routeId, p.trigger, p.shipmentId, p.causes);
            }
        } catch (RuntimeException e) {
            // Zamanlanmış görevin istisnası future'da kalır, kimse okumaz: burada görünür olmalı.
            log.error("rota {} arka plan yeniden rotalamasi beklenmedik sekilde dustu", routeId, e);
        } finally {
            running.remove(routeId);
        }
    }

    private void run(Long routeId, RerouteEvent.Trigger trigger, Long shipmentId, Set<Long> causes) {
        try {
            RerouteService.Outcome out = reroute.reroute(routeId, trigger, shipmentId, causes);
            if (causes.size() > 1) {
                log.info("rota {}: {} degisiklik tek yeniden rotalamada birlesti (yapildi: {})",
                        routeId, causes.size(), out.rerouted());
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
