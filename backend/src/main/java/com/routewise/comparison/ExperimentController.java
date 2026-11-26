package com.routewise.comparison;

import java.util.List;

import com.routewise.auth.AuthenticatedUser;
import com.routewise.comparison.ExperimentService.ExperimentView;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/compare/experiments")
@Tag(name = "Karşılaştırma", description = "Algoritma karşılaştırması (F8)")
public class ExperimentController {

    private final ExperimentService service;

    public ExperimentController(ExperimentService service) {
        this.service = service;
    }

    /** All optional: 30 scenarios of 10 stops within 3 km of the depot, 3 repeats, random seed, first vehicle. */
    public record ExperimentBody(Integer scenarios, Integer stopCount, Integer radiusM, Integer repeats, Long seed,
                                 Long vehicleId) {}

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Deney başlat",
            description = "Tohumdan N sentetik senaryo (gerçek yol grafı, depo çevresinde) üretir; her birinde "
                    + "Seviye 2 tur karşılaştırması koşar ve sonunda RouteWise'ı NAIVE, GREEDY_NN ve SAVINGS ile "
                    + "eşleştirilmiş testlerle (Wilcoxon, t; tek yönlü) karşılaştırır. Arka planda çalışır: yanıt "
                    + "hemen döner, ilerleme GET ile izlenir. Aynı anda tek deney; çalışan varsa 409 "
                    + "EXPERIMENT_RUNNING. Aynı tohum ve parametreler aynı deneyi verir.")
    public ExperimentView start(@RequestBody(required = false) ExperimentBody body,
                                @AuthenticationPrincipal AuthenticatedUser caller) {
        ExperimentBody b = body == null ? new ExperimentBody(null, null, null, null, null, null) : body;
        return service.start(b.scenarios(), b.stopCount(), b.radiusM(), b.repeats(), b.seed(), b.vehicleId(),
                caller.id());
    }

    @GetMapping
    @Operation(summary = "Son deneyler", description = "En yeni 20 deney, özetleriyle (senaryo listesi olmadan).")
    public List<ExperimentView> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Deney", description = "Durum, ilerleme, özet ve senaryo karşılaştırmaları "
            + "(her biri GET /comparisons/{id} ile açılır).")
    public ExperimentView get(@PathVariable Long id) {
        return service.get(id);
    }
}
