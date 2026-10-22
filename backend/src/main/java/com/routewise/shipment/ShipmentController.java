package com.routewise.shipment;

import jakarta.validation.Valid;

import com.routewise.common.PageResponse;
import com.routewise.shipment.ShipmentDtos.CreateRequest;
import com.routewise.shipment.ShipmentDtos.ShipmentResponse;
import com.routewise.shipment.ShipmentDtos.UpdateRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/shipments")
@Tag(name = "Kargo", description = "Kargo listesi ve yönetimi (F3, F4)")
public class ShipmentController {

    private final ShipmentService service;
    private final ShipmentMapper mapper;

    public ShipmentController(ShipmentService service, ShipmentMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(summary = "Kargo listesi", description = "Sayfalı, duruma göre filtrelenebilir.")
    public PageResponse<ShipmentResponse> list(
            @RequestParam(required = false) ShipmentStatus status,
            @RequestParam(required = false) String q,
            @PageableDefault(size = 25, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {

        return PageResponse.of(service.list(status, q, pageable), mapper::toResponse);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Kargo detayı")
    public ShipmentResponse get(@PathVariable Long id) {
        return mapper.toResponse(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Kargo oluştur",
            description = "Sipariş numarası sunucuda üretilir; istekte gönderilmez.")
    public ShipmentResponse create(@Valid @RequestBody CreateRequest req) {
        return mapper.toResponse(service.create(req));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Kargo güncelle", description = "Ağırlık, öncelik, teslim süresi.")
    public ShipmentResponse update(@PathVariable Long id, @Valid @RequestBody UpdateRequest req) {
        return mapper.toResponse(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Kargo sil", description = "Yalnızca planlanmamış kargo silinebilir.")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
