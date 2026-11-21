package com.routewise.comparison;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import com.routewise.common.error.ApiException;
import com.routewise.common.error.ErrorCode;
import com.routewise.cost.CostParameters;
import com.routewise.cost.CostParametersRepository;
import com.routewise.depot.DepotService;
import com.routewise.optimization.client.OptimizerClient;
import com.routewise.optimization.client.OptimizerDtos.CostParamsIn;
import com.routewise.optimization.client.OptimizerDtos.PairIn;
import com.routewise.optimization.client.OptimizerDtos.PairedOut;
import com.routewise.optimization.client.OptimizerDtos.PairedRequest;
import com.routewise.optimization.client.OptimizerDtos.StopIn;
import com.routewise.optimization.client.OptimizerDtos.TourAlgorithmOut;
import com.routewise.optimization.client.OptimizerDtos.TourCompareResult;
import com.routewise.optimization.client.OptimizerDtos.TourScenarioRequest;
import com.routewise.optimization.client.OptimizerDtos.TourScenarioResult;
import com.routewise.optimization.client.OptimizerDtos.VehicleIn;
import com.routewise.vehicle.Vehicle;
import com.routewise.vehicle.VehicleRepository;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs an experiment scenario by scenario, in the background, one experiment at a time.
 *
 * <p>Each scenario is one optimizer call of a few seconds, so no single request
 * outlives the read timeout and progress is visible as it goes. A scenario the
 * optimizer cannot solve (no feasible tour) is counted and skipped; an optimizer
 * that is down aborts the experiment — 30 identical errors teach nothing.
 *
 * <p>The thread is this class's own (like the reroute scheduler): a
 * {@code TaskExecutor} bean would switch off Spring Boot's default one.
 * {@code routewise.experiments.inline=true} runs in the caller's thread (tests).
 */
@Component
public class ExperimentRunner {

    private static final Logger log = LoggerFactory.getLogger(ExperimentRunner.class);

    static final double ALPHA = 0.05;
    static final String CANDIDATE = "ROUTEWISE";
    static final String OPTIMUM = "DFS_EXHAUSTIVE";
    static final List<String> BASELINES = List.of("NAIVE", "GREEDY_NN", "SAVINGS");

    private final ExperimentRepository experiments;
    private final TourComparisonService tours;
    private final OptimizerClient optimizer;
    private final DepotService depots;
    private final VehicleRepository vehicles;
    private final CostParametersRepository costs;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ExecutorService executor;

    public ExperimentRunner(ExperimentRepository experiments, TourComparisonService tours, OptimizerClient optimizer,
                            DepotService depots, VehicleRepository vehicles, CostParametersRepository costs,
                            TransactionTemplate tx, Clock clock,
                            @Value("${routewise.experiments.inline:false}") boolean inline) {
        this.experiments = experiments;
        this.tours = tours;
        this.optimizer = optimizer;
        this.depots = depots;
        this.vehicles = vehicles;
        this.costs = costs;
        this.tx = tx;
        this.clock = clock;
        this.executor = inline ? null : Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "deney");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /** Called after the experiment row is committed. */
    void submit(Long experimentId) {
        if (executor == null) {
            run(experimentId);
        } else {
            executor.execute(() -> run(experimentId));
        }
    }

    // --- koşu --------------------------------------------------------------------

    public record AlgorithmStats(String algorithm, int scenarios, double medianGapPct, double meanGapPct,
                          double maxGapPct, int optimalCount, double medianTimeMs) {}

    /**
     * @param usableScenarios scenarios with a proven optimum (gaps are over it)
     * @param claim           the sentence the report quotes, built from these numbers only
     */
    public record Summary(int usableScenarios, double alpha, List<AlgorithmStats> algorithms, List<PairedOut> tests,
                   String claim) {}

    void run(Long id) {
        try {
            Setup s = tx.execute(status -> setup(id));
            List<TourCompareResult> results = new ArrayList<>();
            for (int i = 0; i < s.scenarios(); i++) {
                long seed = s.baseSeed() + i;
                TourScenarioResult r;
                try {
                    r = optimizer.tourScenario(new TourScenarioRequest(s.depotNode(), seed, s.stopCount(), s.radiusM(),
                            s.vehicle(), s.price(), s.workdayS(), s.repeats()));
                } catch (ApiException e) {
                    if (e.code() != ErrorCode.NO_FEASIBLE_SOLUTION) {
                        throw e;
                    }
                    tx.executeWithoutResult(status -> experiments.findById(id).orElseThrow()
                            .scenarioFailed("Senaryo " + seed + ": " + e.getMessage()));
                    continue;
                }
                results.add(r.comparison());
                tx.executeWithoutResult(status -> {
                    List<Long> stopIds = r.stops().stream().map(StopIn::id).toList();
                    tours.record(Comparison.scenario(id, seed, tours.scenarioParams(s.vehicleId(), stopIds,
                                    s.repeats(), seed), tours.json(r.comparison()), s.createdBy(), clock.instant()),
                            r.comparison(), r.stops().size() - r.comparison().unassigned().size(), clock.instant());
                    experiments.findById(id).orElseThrow().scenarioDone();
                });
            }
            Summary summary = summarize(results);
            tx.executeWithoutResult(status -> experiments.findById(id).orElseThrow()
                    .finish(tours.json(summary), clock.instant()));
            log.info("deney {} bitti: {} senaryo, {}", id, summary.usableScenarios(), summary.claim());
        } catch (RuntimeException e) {
            log.error("deney {} yarida kaldi", id, e);
            String why = e instanceof ApiException api ? api.getMessage() : "Beklenmeyen hata: " + e.getMessage();
            tx.executeWithoutResult(status -> experiments.findById(id).ifPresent(x -> x.abort(why, clock.instant())));
        }
    }

    private record Setup(int scenarios, long baseSeed, int stopCount, double radiusM, int repeats, long depotNode,
                         Long vehicleId, VehicleIn vehicle, CostParamsIn price, double workdayS, Long createdBy) {}

    private Setup setup(Long id) {
        Experiment e = experiments.findById(id).orElseThrow();
        e.start(clock.instant());
        var depot = depots.primary();
        Vehicle v = vehicles.findById(e.getVehicleId()).orElseThrow(() -> ApiException.notFound("Araç"));
        CostParameters p = costs.findFirstByEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                        java.time.LocalDate.now(clock))
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR, "Bugün için maliyet parametresi yok."));
        return new Setup(e.getScenarioCount(), e.getBaseSeed(), e.getStopCount(), e.getRadiusM(), e.getRepeats(),
                depot.getSnappedNodeId(), v.getId(),
                new VehicleIn(v.getId(), v.getCapacityKg().doubleValue(), v.getFuelLPer100km().doubleValue(),
                        v.getFixedCostPerDay().doubleValue()),
                new CostParamsIn(p.getFuelPricePerL().doubleValue(), p.getDriverCostPerHour().doubleValue(),
                        p.getPriorityPenalty().doubleValue()),
                depot.getMaxRouteDurationMin() * 60.0, e.getCreatedBy());
    }

    // --- özet ----------------------------------------------------------------------

    Summary summarize(List<TourCompareResult> results) {
        // Sapma yalnızca kanıtlı optimuma göre ölçüldüğünde iddiaya girer.
        List<Map<String, TourAlgorithmOut>> usable = results.stream()
                .filter(TourCompareResult::referenceIsOptimal)
                .map(r -> {
                    Map<String, TourAlgorithmOut> m = new LinkedHashMap<>();
                    r.results().forEach(a -> m.put(a.algorithm(), a));
                    return m;
                })
                .toList();

        List<String> order = List.of("NAIVE", "GREEDY_NN", "SAVINGS", CANDIDATE, OPTIMUM);
        List<AlgorithmStats> stats = order.stream().map(alg -> {
            List<TourAlgorithmOut> ok = usable.stream().map(m -> m.get(alg))
                    .filter(a -> a != null && a.feasible() && a.gapPct() != null).toList();
            List<Double> gaps = ok.stream().map(TourAlgorithmOut::gapPct).sorted().toList();
            return new AlgorithmStats(alg, ok.size(), median(gaps), mean(gaps),
                    gaps.isEmpty() ? 0 : gaps.get(gaps.size() - 1),
                    (int) gaps.stream().filter(g -> g < 1e-6).count(),
                    median(ok.stream().map(TourAlgorithmOut::computationTimeMs).sorted().toList()));
        }).filter(a -> a.scenarios() > 0).toList();

        List<PairIn> pairs = new ArrayList<>();
        for (String base : BASELINES) {
            List<Double> cand = new ArrayList<>();
            List<Double> ref = new ArrayList<>();
            for (Map<String, TourAlgorithmOut> m : usable) {
                TourAlgorithmOut c = m.get(CANDIDATE);
                TourAlgorithmOut b = m.get(base);
                if (c != null && b != null && c.solution() != null && b.solution() != null) {
                    cand.add(c.solution().objective());
                    ref.add(b.solution().objective());
                }
            }
            if (cand.size() >= 2) {
                pairs.add(new PairIn(CANDIDATE, base, cand, ref));
            }
        }
        List<PairedOut> tests = pairs.isEmpty() ? List.of()
                : optimizer.pairedTests(new PairedRequest(pairs, ALPHA)).results();
        return new Summary(usable.size(), ALPHA, stats, tests, claim(usable.size(), stats, tests));
    }

    private static String claim(int n, List<AlgorithmStats> stats, List<PairedOut> tests) {
        AlgorithmStats rw = stats.stream().filter(a -> a.algorithm().equals(CANDIDATE)).findFirst().orElse(null);
        if (n == 0 || rw == null) {
            return "Kanıtlı optimumla karşılaştırılabilen senaryo yok.";
        }
        StringBuilder sb = new StringBuilder("RouteWise, %d senaryoda kanıtlı optimumun ortalama %%%s, en kötü %%%s içinde; %d senaryoda optimumu buldu."
                .formatted(rw.scenarios(), pct(rw.meanGapPct()), pct(rw.maxGapPct()), rw.optimalCount()));
        for (PairedOut t : tests) {   // BASELINES sırası: en zayıftan en güçlüye
            sb.append(" %s karşısında %s (Wilcoxon p = %s, n = %d).".formatted(label(t.reference()),
                    t.significant() ? "anlamlı ölçüde daha ucuz" : "fark anlamlı değil", p(t.wilcoxonP()), t.n()));
        }
        return sb.toString();
    }

    private static String label(String alg) {
        return switch (alg) {
            case "NAIVE" -> "Geliş sırası";
            case "GREEDY_NN" -> "En yakın komşu";
            case "SAVINGS" -> "Clarke-Wright";
            default -> alg;
        };
    }

    private static String pct(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v).replace('.', ',');
    }

    private static String p(double v) {
        return v < 0.0001 ? "< 0,0001" : String.format(java.util.Locale.ROOT, "%.4f", v).replace('.', ',');
    }

    private static double median(List<Double> sorted) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    private static double mean(List<Double> values) {
        return values.isEmpty() ? 0 : IntStream.range(0, values.size()).mapToDouble(values::get).average().orElse(0);
    }
}
