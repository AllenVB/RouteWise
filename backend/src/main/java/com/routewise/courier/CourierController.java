package com.routewise.courier;

import java.time.LocalDate;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.routewise.auth.AuthenticatedUser;
import com.routewise.common.error.ApiException;
import com.routewise.common.error.ErrorCode;
import com.routewise.optimization.RunDtos.RunDetail;
import com.routewise.optimization.RunDtos.StopDetail;
import com.routewise.user.Role;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Courier screens K2–K4. The security filter lets couriers and the company in;
 * the company may read (with an explicit courier) but never close a stop.
 */
@RestController
@RequestMapping("/courier")
@Tag(name = "Kurye", description = "Kuryenin günlük rotası ve durakları (K2–K4)")
public class CourierController {

    private final CourierService service;

    public CourierController(CourierService service) {
        this.service = service;
    }

    public record FailRequest(
            @NotBlank(message = "Sebep gerekli.")
            @Size(max = 300, message = "Sebep en fazla 300 karakter olabilir.")
            String reason) {}

    @GetMapping("/route")
    @Operation(summary = "Günün rotası",
            description = "Kurye kendi rotasını görür; firma `courierId` vererek okur. Tarih verilmezse bugün (İstanbul).")
    public RunDetail route(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                           @RequestParam(required = false) Long courierId,
                           @AuthenticationPrincipal AuthenticatedUser caller) {
        LocalDate day = date == null ? service.today() : date;
        return service.route(readerCourier(caller, courierId), day);
    }

    @GetMapping("/stops/{id}")
    @Operation(summary = "Durak detayı", description = "Alıcı adı ve telefonuyla; başkasının durağı 404.")
    public StopDetail stop(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser caller) {
        Long courierId = caller.role() == Role.COURIER ? service.courierIdOf(caller.id()) : null;
        return service.stop(courierId, id);
    }

    @PostMapping("/stops/{id}/deliver")
    @Operation(summary = "Teslim edildi",
            description = "İlk kapanan durak rotayı yola çıkarır; son durak rotayı tamamlar. Kapalı durak 409.")
    public StopDetail deliver(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser caller) {
        return service.deliver(ownCourier(caller), id);
    }

    @PostMapping("/stops/{id}/fail")
    @Operation(summary = "Ulaşılamadı", description = "Sebep zorunlu; kargo havuza döner ve başka gün planlanabilir.")
    public StopDetail fail(@PathVariable Long id, @Valid @RequestBody FailRequest body,
                           @AuthenticationPrincipal AuthenticatedUser caller) {
        return service.fail(ownCourier(caller), id, body.reason().trim());
    }

    private Long readerCourier(AuthenticatedUser caller, Long courierId) {
        if (caller.role() == Role.COURIER) {
            return service.courierIdOf(caller.id());   // kurye başkasının rotasını isteyemez
        }
        if (courierId == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Firma için courierId gerekli.");
        }
        return courierId;
    }

    /** Duraklar yalnızca kurye tarafından kapatılır; firma okur. */
    private Long ownCourier(AuthenticatedUser caller) {
        if (caller.role() != Role.COURIER) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Durakları yalnızca kurye kapatabilir.");
        }
        return service.courierIdOf(caller.id());
    }
}
