package com.routewise.vehicle;

import java.math.BigDecimal;
import java.util.List;

import com.routewise.depot.DepotService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/vehicles")
@Tag(name = "Araç", description = "Filo — salt okunur, seed ile yüklenir (F6)")
public class VehicleController {

    private final VehicleRepository vehicles;
    private final DepotService depots;

    public VehicleController(VehicleRepository vehicles, DepotService depots) {
        this.vehicles = vehicles;
        this.depots = depots;
    }

    public record VehicleResponse(
            Long id,
            String plate,
            BigDecimal capacityKg,
            BigDecimal capacityVolumeM3,
            BigDecimal fuelLPer100km,
            String fuelType,
            BigDecimal fixedCostPerDay) {}

    @GetMapping
    @Operation(summary = "Aktif filo", description = "Deponun aktif araçları.")
    public List<VehicleResponse> list() {
        return vehicles.findByDepotIdAndActiveTrueOrderByIdAsc(depots.primary().getId()).stream()
                .map(v -> new VehicleResponse(v.getId(), v.getPlate(), v.getCapacityKg(),
                        v.getCapacityVolumeM3(), v.getFuelLPer100km(), v.getFuelType(),
                        v.getFixedCostPerDay()))
                .toList();
    }
}
