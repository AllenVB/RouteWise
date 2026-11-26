package com.routewise.analytics;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.routewise.optimization.client.OptimizerDtos.PathStatIn;
import com.routewise.optimization.client.OptimizerDtos.PlanRowIn;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Repository;

/**
 * Read-only queries behind the savings report (F9). Native SQL: percentiles and
 * the "baseline of the same optimization" pairing have no JPQL equivalent.
 */
@Repository
public class AnalyticsRepository {

    private final EntityManager em;

    public AnalyticsRepository(EntityManager em) {
        this.em = em;
    }

    /**
     * One row per dispatched plan (what was actually driven) in {@code [from, to]}.
     *
     * <p>Each optimization writes the naive baseline right before its result, on the same
     * shipments; the applied run is paired with the latest baseline at or before it. When
     * NAIVE itself was applied, the run is its own baseline and saves nothing.
     */
    @SuppressWarnings("unchecked")
    public List<PlanRowIn> planRows(LocalDate from, LocalDate to) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT p.plan_date, a.total_cost_try, b.total_cost_try, a.total_distance_m, b.total_distance_m,
                       COALESCE(a.vehicle_count, 0),
                       (SELECT count(*) FROM route_stops s JOIN routes rt ON rt.id = s.route_id WHERE rt.run_id = a.id)
                FROM delivery_plans p
                JOIN optimization_runs a ON a.plan_id = p.id AND a.is_applied
                JOIN LATERAL (
                    SELECT x.total_cost_try, x.total_distance_m FROM optimization_runs x
                    WHERE x.plan_id = p.id AND x.is_baseline AND x.id <= a.id
                    ORDER BY x.id DESC LIMIT 1
                ) b ON true
                WHERE p.status IN ('SEVK_EDILDI', 'TAMAMLANDI')
                  AND p.plan_date BETWEEN :from AND :to
                  AND a.total_cost_try IS NOT NULL AND b.total_cost_try IS NOT NULL
                ORDER BY p.plan_date
                """)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
        return rows.stream().map(r -> new PlanRowIn(
                toDate(r[0]), num(r[1]), num(r[2]), num(r[3]), num(r[4]),
                ((Number) r[5]).intValue(), ((Number) r[6]).intValue())).toList();
    }

    /** Level-1 comparisons in {@code [from, to)}: per algorithm, only runs that found a path. */
    @SuppressWarnings("unchecked")
    public List<PathStatIn> pathStats(Instant from, Instant to) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT algorithm, count(*),
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY deviation_pct),
                       max(deviation_pct),
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY nodes_visited),
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY computation_time_ms)
                FROM path_comparison_runs
                WHERE found AND deviation_pct IS NOT NULL AND created_at >= :from AND created_at < :to
                GROUP BY algorithm
                ORDER BY array_position(ARRAY['DIJKSTRA', 'ASTAR', 'BFS', 'DFS']::varchar[], algorithm)
                """)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
        return rows.stream().map(r -> new PathStatIn((String) r[0], ((Number) r[1]).intValue(), num(r[2]),
                num(r[3]), num(r[4]), num(r[5]))).toList();
    }

    /** Gap over the proven optimum per scenario, by algorithm, for one experiment. */
    @SuppressWarnings("unchecked")
    public Map<String, List<Double>> experimentGaps(Long experimentId) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT r.algorithm, r.gap_pct
                FROM tour_comparison_runs r JOIN comparisons c ON c.id = r.comparison_id
                WHERE c.experiment_id = :id AND r.reference_is_optimal AND r.feasible AND r.gap_pct IS NOT NULL
                ORDER BY array_position(ARRAY['NAIVE', 'GREEDY_NN', 'SAVINGS', 'ROUTEWISE', 'DFS_EXHAUSTIVE']::varchar[],
                                        r.algorithm), c.scenario_seed
                """)
                .setParameter("id", experimentId)
                .getResultList();
        Map<String, List<Double>> out = new LinkedHashMap<>();
        for (Object[] r : rows) {
            out.computeIfAbsent((String) r[0], k -> new java.util.ArrayList<>()).add(num(r[1]));
        }
        return out;
    }

    private static double num(Object o) {
        return o == null ? 0.0 : ((Number) o).doubleValue();
    }

    private static LocalDate toDate(Object o) {
        return o instanceof java.sql.Date d ? d.toLocalDate() : (LocalDate) o;
    }
}
