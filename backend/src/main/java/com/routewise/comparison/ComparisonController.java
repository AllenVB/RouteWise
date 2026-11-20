package com.routewise.comparison;

import java.util.List;

import com.routewise.address.AddressDtos.Coordinate;
import com.routewise.auth.AuthenticatedUser;
import com.routewise.common.error.ApiException;
import com.routewise.comparison.PathComparisonService.PathComparison;
import com.routewise.comparison.PathComparisonService.Weight;
import com.routewise.comparison.TourComparisonService.TourComparison;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Karşılaştırma", description = "Algoritma karşılaştırması (F8)")
public class ComparisonController {

    private final PathComparisonService paths;
    private final TourComparisonService tours;
    private final ComparisonRepository comparisons;

    public ComparisonController(PathComparisonService paths, TourComparisonService tours,
                                ComparisonRepository comparisons) {
        this.paths = paths;
        this.tours = tours;
        this.comparisons = comparisons;
    }

    /** Each end: a map point ({@code from}) or a road node id ({@code fromNodeId}), not both. */
    public record PathCompareBody(@Valid Coordinate from, Long fromNodeId, @Valid Coordinate to, Long toNodeId,
                                  Weight weight, Integer repeats) {}

    /**
     * @param vehicleId   the vehicle whose capacity and costs apply; default the depot's first active one
     * @param shipmentIds a subset of the plan's routable shipments (small scenarios); default all of them
     */
    public record TourCompareBody(Long vehicleId, List<Long> shipmentIds, Integer repeats, Long randomSeed) {}

    @PostMapping("/compare/path")
    @Operation(summary = "Seviye 1 — yol bulma karşılaştırması",
            description = "Dijkstra, A*, BFS ve DFS aynı iki yol düğümü arasında koşar. Her uç haritadan nokta "
                    + "(en yakın yol düğümüne bağlanır) ya da düğüm kimliği olarak verilir. Her algoritma "
                    + "`repeats` kez (varsayılan 5, en fazla 20) koşar, süre medyanı raporlanır. Sapma seçilen "
                    + "ağırlıkta (DISTANCE / DURATION) Dijkstra'ya göre yüzdedir.")
    public PathComparison comparePath(@Valid @RequestBody PathCompareBody body,
                                      @AuthenticationPrincipal AuthenticatedUser caller) {
        return paths.compare(body.from(), body.fromNodeId(), body.to(), body.toNodeId(), body.weight(),
                body.repeats(), caller.id());
    }

    @PostMapping("/plans/{id}/compare/tour")
    @Operation(summary = "Seviye 2 — tur sırası karşılaştırması",
            description = "Tek araç, planın rotaya alınabilir kargoları (ya da `shipmentIds` alt kümesi, en fazla "
                    + "100): NAIVE, GREEDY_NN, SAVINGS, ROUTEWISE ve en fazla 10 durakta DFS_EXHAUSTIVE (kanıtlı "
                    + "optimum). Her algoritma `repeats` kez (varsayılan 3, en fazla 10) koşar; ROUTEWISE her "
                    + "tekrarda başka tohumla, amacı medyan olan koşu raporlanır. Sapma referansa göredir: "
                    + "`referenceIsOptimal` false ise referans bulunan en iyi çözümdür. Plan değişmez. "
                    + "Kargolar tek araca ya da çalışma gününe sığmıyorsa 409 NO_FEASIBLE_SOLUTION.")
    public TourComparison compareTour(@PathVariable Long id, @RequestBody(required = false) TourCompareBody body,
                                      @AuthenticationPrincipal AuthenticatedUser caller) {
        TourCompareBody b = body == null ? new TourCompareBody(null, null, null, null) : body;
        return tours.compare(id, b.vehicleId(), b.shipmentIds(), b.repeats(), b.randomSeed(), caller.id());
    }

    @GetMapping("/comparisons/{id}")
    @Operation(summary = "Karşılaştırma sonucu",
            description = "Kaydedilmiş bir karşılaştırma, hesaplandığı gibi. `level` PATH (Seviye 1) ya da TOUR "
                    + "(Seviye 2) yanıtın biçimini belirler.")
    public ComparisonView get(@PathVariable Long id) {
        Comparison c = comparisons.findById(id).orElseThrow(() -> ApiException.notFound("Karşılaştırma"));
        return switch (c.getLevel()) {
            case PATH -> paths.view(c);
            case TOUR -> tours.view(c);
        };
    }
}
