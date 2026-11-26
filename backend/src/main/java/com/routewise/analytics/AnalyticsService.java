package com.routewise.analytics;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.routewise.common.error.ApiException;
import com.routewise.common.error.ErrorCode;
import com.routewise.comparison.ExperimentService;
import com.routewise.comparison.ExperimentService.ExperimentView;
import com.routewise.optimization.client.OptimizerClient;
import com.routewise.optimization.client.OptimizerDtos.ExperimentAlgorithmIn;
import com.routewise.optimization.client.OptimizerDtos.ExperimentIn;
import com.routewise.optimization.client.OptimizerDtos.ExperimentTestIn;
import com.routewise.optimization.client.OptimizerDtos.ExportRequest;
import com.routewise.optimization.client.OptimizerDtos.PlanRowIn;
import com.routewise.optimization.client.OptimizerDtos.SavingsRequest;
import com.routewise.optimization.client.OptimizerDtos.SavingsResult;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * F9 — tasarruf raporları (roadmap phase 6, tasks 8 and 11).
 *
 * <p>Savings are what was actually driven: only dispatched plans count, each
 * against the naive baseline of its own shipments. The backend reads, the
 * optimizer aggregates (pandas) and renders the files (openpyxl, matplotlib);
 * the numbers in a report all come from these queries.
 */
@Service
public class AnalyticsService {

    public enum Period { WEEK, MONTH, YEAR }

    public enum Format {
        XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        PDF("pdf", "application/pdf");

        public final String extension;
        public final String mediaType;

        Format(String extension, String mediaType) {
            this.extension = extension;
            this.mediaType = mediaType;
        }
    }

    /** A report spanning more is a data dump, not a report; and the row cap keeps the request bounded. */
    static final long MAX_RANGE_DAYS = 10 * 366;

    private final AnalyticsRepository repo;
    private final ExperimentService experiments;
    private final OptimizerClient optimizer;
    private final TransactionTemplate tx;
    private final Clock clock;

    public AnalyticsService(AnalyticsRepository repo, ExperimentService experiments, OptimizerClient optimizer,
                            TransactionTemplate tx, Clock clock) {
        this.repo = repo;
        this.experiments = experiments;
        this.optimizer = optimizer;
        this.tx = tx;
        this.clock = clock;
    }

    public record Range(LocalDate from, LocalDate to) {}

    public record Savings(Period period, LocalDate from, LocalDate to, SavingsResult result) {}

    public record Report(String filename, String mediaType, byte[] content) {}

    public Savings savings(Period period, LocalDate from, LocalDate to) {
        Range r = range(period, from, to);
        List<PlanRowIn> rows = tx.execute(status -> repo.planRows(r.from(), r.to()));
        return new Savings(period, r.from(), r.to(), optimizer.savings(new SavingsRequest(period.name(), rows)));
    }

    public Report export(Format format, Period period, LocalDate from, LocalDate to) {
        Range r = range(period, from, to);
        ZoneId zone = clock.getZone();
        ExportRequest req = tx.execute(status -> {
            ExperimentIn experiment = experiments.latestDone()
                    .filter(e -> e.summary() != null)
                    .map(this::experiment)
                    .orElse(null);
            return new ExportRequest(format.name(), period.name(), r.from(), r.to(), LocalDateTime.now(clock),
                    repo.planRows(r.from(), r.to()),
                    repo.pathStats(r.from().atStartOfDay(zone).toInstant(),
                            r.to().plusDays(1).atStartOfDay(zone).toInstant()),
                    experiment);
        });
        byte[] file = optimizer.exportReport(req);
        String name = "routewise-rapor-%s-%s.%s".formatted(r.from(), r.to(), format.extension);
        return new Report(name, format.mediaType, file);
    }

    private ExperimentIn experiment(ExperimentView e) {
        var s = e.summary();
        return new ExperimentIn(e.id(), e.baseSeed(), e.completed(), e.stopCount(),
                s.algorithms().stream().map(a -> new ExperimentAlgorithmIn(a.algorithm(), a.scenarios(),
                        a.medianGapPct(), a.meanGapPct(), a.maxGapPct(), a.optimalCount(), a.medianTimeMs())).toList(),
                s.tests().stream().map(t -> new ExperimentTestIn(t.candidate(), t.reference(), t.n(),
                        t.candidateBetter(), t.ties(), t.meanDiffPct(), t.wilcoxonP(), t.tP(), t.significant()))
                        .toList(),
                s.claim(), repo.experimentGaps(e.id()));
    }

    /** Defaults: up to today, 12 weeks / 12 months / 5 years back. */
    Range range(Period period, LocalDate from, LocalDate to) {
        LocalDate end = to == null ? LocalDate.now(clock) : to;
        LocalDate start = from != null ? from : switch (period) {
            case WEEK -> end.minusWeeks(12);
            case MONTH -> end.minusMonths(12);
            case YEAR -> end.minusYears(5);
        };
        if (start.isAfter(end)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Başlangıç tarihi bitişten sonra olamaz.");
        }
        if (ChronoUnit.DAYS.between(start, end) > MAX_RANGE_DAYS) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Rapor aralığı en fazla 10 yıl olabilir.");
        }
        return new Range(start, end);
    }
}
