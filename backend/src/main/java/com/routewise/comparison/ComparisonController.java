package com.routewise.comparison;

import com.routewise.address.AddressDtos.Coordinate;
import com.routewise.auth.AuthenticatedUser;
import com.routewise.comparison.PathComparisonService.PathComparison;
import com.routewise.comparison.PathComparisonService.Weight;

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

    public ComparisonController(PathComparisonService paths) {
        this.paths = paths;
    }

    /** Each end: a map point ({@code from}) or a road node id ({@code fromNodeId}), not both. */
    public record PathCompareBody(@Valid Coordinate from, Long fromNodeId, @Valid Coordinate to, Long toNodeId,
                                  Weight weight, Integer repeats) {}

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

    @GetMapping("/comparisons/{id}")
    @Operation(summary = "Karşılaştırma sonucu", description = "Kaydedilmiş bir karşılaştırma, hesaplandığı gibi.")
    public PathComparison get(@PathVariable Long id) {
        return paths.get(id);
    }
}
