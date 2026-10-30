package com.routewise.plan;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import com.routewise.auth.AuthenticatedUser;
import com.routewise.optimization.OptimizationService;
import com.routewise.optimization.RunDtos.OptimizeRequest;
import com.routewise.optimization.RunDtos.OptimizeResponse;
import com.routewise.optimization.RunDtos.RunSummary;
import com.routewise.optimization.RunService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/plans")
@Tag(name = "Plan", description = "Günlük dağıtım planı ve optimizasyon (F6)")
public class PlanController {

    private final PlanService plans;
    private final OptimizationService optimization;
    private final RunService runs;

    public PlanController(PlanService plans, OptimizationService optimization, RunService runs) {
        this.plans = plans;
        this.optimization = optimization;
        this.runs = runs;
    }

    public record OpenPlanRequest(@NotNull(message = "Tarih gerekli.") LocalDate date) {}

    public record PlanResponse(Long id, LocalDate date, PlanStatus status, Instant createdAt) {
        static PlanResponse of(DeliveryPlan p) {
            return new PlanResponse(p.getId(), p.getPlanDate(), p.getStatus(), p.getCreatedAt());
        }
    }

    @GetMapping
    @Operation(summary = "Günün planı", description = "Plan yoksa 404.")
    public PlanResponse forDate(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return PlanResponse.of(plans.forDate(date));
    }

    @PostMapping
    @Operation(summary = "Plan aç",
            description = "Aynı gün için tekrar çağrılırsa mevcut planı 200 ile döndürür; yeni plan 201.")
    public ResponseEntity<PlanResponse> open(@Valid @RequestBody OpenPlanRequest body,
                                             @AuthenticationPrincipal AuthenticatedUser caller) {
        PlanService.Opened opened = plans.open(body.date(), caller.id());
        return ResponseEntity.status(opened.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(PlanResponse.of(opened.plan()));
    }

    @PostMapping("/{id}/optimize")
    @Operation(summary = "Planı optimize et",
            description = """
                    Doğrulanmış adresli kargolar tüm aktif filoya dağıtılır (CVRP) ve
                    aynı kümede naive baseline hesaplanır. Varsayılan algoritma ROUTEWISE.
                    Yeni koşu uygulanan koşu olur. Adresi doğrulanmamış veya depoya
                    yolu olmayan kargolar `excluded` içinde sebebiyle döner.
                    `savingsPct` gerçek TL maliyet üzerindendir (öncelik cezası hariç).
                    """)
    public OptimizeResponse optimize(@PathVariable Long id,
                                     @RequestBody(required = false) OptimizeRequest body) {
        return optimization.optimize(id, body);
    }

    @GetMapping("/{id}/runs")
    @Operation(summary = "Plana ait koşular", description = "Yeniden eskiye.")
    public List<RunSummary> runs(@PathVariable Long id) {
        return runs.forPlan(id);
    }
}
