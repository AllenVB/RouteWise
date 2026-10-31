package com.routewise.optimization;

import com.routewise.optimization.RunDtos.RunDetail;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Koşu", description = "Optimizasyon koşuları — metrikler, rotalar, geometri (F6)")
public class RunController {

    private final RunService service;

    public RunController(RunService service) {
        this.service = service;
    }

    @GetMapping("/routes/{id}")
    @Operation(summary = "Rota detayı (F7)",
            description = "Tek rotanın sıralı durakları, yük, maliyet ve geometrisi; koşu detayıyla aynı biçimde.")
    public RunDetail route(@PathVariable Long id) {
        return service.route(id);
    }

    @GetMapping("/runs/{id}")
    @Operation(summary = "Koşu detayı",
            description = """
                    Metrikler, araç bazlı sıralı duraklar ve haritada çizilecek yol
                    geometrisi (GeoJSON LineString). Rota servisine ulaşılamazsa
                    duraklar yine döner, `geometryAvailable=false` olur.
                    """)
    public RunDetail get(@PathVariable Long id) {
        return service.detail(id);
    }
}
