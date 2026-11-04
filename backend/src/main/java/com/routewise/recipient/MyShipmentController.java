package com.routewise.recipient;

import java.util.List;

import com.routewise.auth.AuthenticatedUser;
import com.routewise.recipient.MyShipmentService.MyShipment;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/me/shipments")
@Tag(name = "Alıcı — kargolarım", description = "Alıcının kendi kargoları (A2, A3)")
public class MyShipmentController {

    private final MyShipmentService service;

    public MyShipmentController(MyShipmentService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Kargolarım", description = "Yeniden eskiye; sevk edildiyse tahmini teslim saatiyle.")
    public List<MyShipment> list(@AuthenticationPrincipal AuthenticatedUser caller) {
        return service.list(caller.id());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Kargo detayı", description = "Başkasının kargosu 404 — var olduğu sızdırılmaz.")
    public MyShipment get(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser caller) {
        return service.get(caller.id(), id);
    }
}
