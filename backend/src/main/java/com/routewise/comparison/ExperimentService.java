package com.routewise.comparison;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.routewise.common.error.ApiException;
import com.routewise.common.error.ErrorCode;
import com.routewise.comparison.ExperimentRunner.Summary;
import com.routewise.depot.DepotService;
import com.routewise.vehicle.Vehicle;
import com.routewise.vehicle.VehicleRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Analiz deneyleri (roadmap phase 6, tasks 8–9): N synthetic scenarios on the real
 * road graph, every tour algorithm on each, paired significance tests at the end.
 *
 * <p>One experiment at a time: they share the optimizer with live planning, and
 * two running side by side would only make both slower.
 */
@Service
public class ExperimentService {

    private static final Logger log = LoggerFactory.getLogger(ExperimentService.class);

    static final int MIN_SCENARIOS = 5;
    static final int MAX_SCENARIOS = 100;
    static final int DEFAULT_SCENARIOS = 30;
    static final int MIN_STOPS = 3;
    static final int MAX_STOPS = 10;
    static final int MIN_RADIUS_M = 500;
    static final int MAX_RADIUS_M = 15_000;
    static final int DEFAULT_RADIUS_M = 3000;
    static final int MAX_REPEATS = 5;
    static final int DEFAULT_REPEATS = 3;
    /** The optimizer takes seeds below 2^31; the last scenario's seed must fit too. */
    static final long MAX_SEED = (1L << 31) - MAX_SCENARIOS - 1;

    private static final Set<Experiment.Status> ACTIVE = EnumSet.of(Experiment.Status.QUEUED, Experiment.Status.RUNNING);

    private final ExperimentRepository experiments;
    private final ComparisonRepository comparisons;
    private final ExperimentRunner runner;
    private final DepotService depots;
    private final VehicleRepository vehicles;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ExperimentService(ExperimentRepository experiments, ComparisonRepository comparisons,
                             ExperimentRunner runner, DepotService depots, VehicleRepository vehicles,
                             ObjectMapper json, TransactionTemplate tx, Clock clock) {
        this.experiments = experiments;
        this.comparisons = comparisons;
        this.runner = runner;
        this.depots = depots;
        this.vehicles = vehicles;
        this.json = json;
        this.tx = tx;
        this.clock = clock;
    }

    public record ScenarioRef(Long comparisonId, Long seed) {}

    /** @param scenarios null in the list view; the run's comparisons in seed order otherwise */
    public record ExperimentView(Long id, Experiment.Status status, int scenarioCount, int stopCount, int radiusM,
                                 int repeats, long baseSeed, Long vehicleId, int completed, int failed,
                                 String lastError, Instant createdAt, Instant startedAt, Instant finishedAt,
                                 Summary summary, List<ScenarioRef> scenarios) {}

    public ExperimentView start(Integer scenarios, Integer stopCount, Integer radiusM, Integer repeats, Long seed,
                                Long vehicleId, Long userId) {
        int n = within("Senaryo sayısı", scenarios, DEFAULT_SCENARIOS, MIN_SCENARIOS, MAX_SCENARIOS);
        int stops = within("Durak sayısı", stopCount, MAX_STOPS, MIN_STOPS, MAX_STOPS);
        int radius = within("Yarıçap (m)", radiusM, DEFAULT_RADIUS_M, MIN_RADIUS_M, MAX_RADIUS_M);
        int reps = within("Tekrar sayısı", repeats, DEFAULT_REPEATS, 1, MAX_REPEATS);
        long base = seed == null ? java.util.concurrent.ThreadLocalRandom.current().nextLong(MAX_SEED) : seed;
        if (base < 0 || base > MAX_SEED) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Tohum 0 ile " + MAX_SEED + " arasında olmalı.");
        }

        Experiment saved = tx.execute(status -> {
            if (experiments.existsByStatusIn(ACTIVE)) {
                throw new ApiException(ErrorCode.EXPERIMENT_RUNNING,
                        "Bir deney zaten çalışıyor; bitince yenisini başlatabilirsin.");
            }
            Vehicle v = vehicle(vehicleId);
            return experiments.save(new Experiment(n, stops, radius, reps, base, v.getId(), userId, clock.instant()));
        });
        log.info("deney {} basladi: {} senaryo, {} durak, {} m, {}x, tohum {}", saved.getId(), n, stops, radius, reps, base);
        // Satır commit edildikten sonra: arka plan iş parçacığı onu okuyabilsin.
        runner.submit(saved.getId());
        return get(saved.getId());
    }

    @Transactional(readOnly = true)
    public ExperimentView get(Long id) {
        Experiment e = experiments.findById(id).orElseThrow(() -> ApiException.notFound("Deney"));
        List<ScenarioRef> refs = comparisons.findByExperimentIdOrderByScenarioSeedAsc(id).stream()
                .map(c -> new ScenarioRef(c.getId(), c.getScenarioSeed())).toList();
        return view(e, refs);
    }

    @Transactional(readOnly = true)
    public List<ExperimentView> list() {
        return experiments.findTop20ByOrderByIdDesc().stream().map(e -> view(e, null)).toList();
    }

    /** Runs live only in this process's memory: one cut short by a restart will not resume. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void abandonInterrupted() {
        List<Experiment> stale = experiments.findByStatusIn(ACTIVE);
        stale.forEach(e -> e.abort("Sunucu yeniden başladı; deney yarıda kaldı. Aynı tohumla yeniden başlatılabilir.",
                clock.instant()));
        if (!stale.isEmpty()) {
            log.warn("{} yarim kalan deney FAILED olarak isaretlendi", stale.size());
        }
    }

    private Vehicle vehicle(Long vehicleId) {
        List<Vehicle> active = vehicles.findByDepotIdAndActiveTrueOrderByIdAsc(depots.primary().getId());
        if (vehicleId == null) {
            return active.stream().findFirst()
                    .orElseThrow(() -> new ApiException(ErrorCode.NO_FEASIBLE_SOLUTION, "Depoda aktif araç yok."));
        }
        return active.stream().filter(v -> v.getId().equals(vehicleId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Aktif araç"));
    }

    private ExperimentView view(Experiment e, List<ScenarioRef> refs) {
        Summary summary = null;
        if (e.getSummary() != null) {
            try {
                summary = json.readValue(e.getSummary(), Summary.class);
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("deney ozeti okunamadi", ex);
            }
        }
        return new ExperimentView(e.getId(), e.getStatus(), e.getScenarioCount(), e.getStopCount(), e.getRadiusM(),
                e.getRepeats(), e.getBaseSeed(), e.getVehicleId(), e.getCompleted(), e.getFailed(), e.getLastError(),
                e.getCreatedAt(), e.getStartedAt(), e.getFinishedAt(), summary, refs);
    }

    private static int within(String what, Integer value, int fallback, int min, int max) {
        int v = value == null ? fallback : value;
        if (v < min || v > max) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, what + " " + min + " ile " + max + " arasında olmalı.");
        }
        return v;
    }
}
