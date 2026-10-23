package com.routewise.address;

import java.util.List;

import jakarta.validation.Valid;

import com.routewise.address.AddressDtos.AddressResponse;
import com.routewise.address.AddressDtos.Coordinate;
import com.routewise.address.AddressDtos.RejectRequest;
import com.routewise.address.AddressDtos.ValidationResult;
import com.routewise.auth.AuthenticatedUser;
import com.routewise.common.geo.Points;
import com.routewise.recipient.RecipientRepository;
import com.routewise.user.UserRepository;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/addresses")
@Tag(name = "Adres", description = "Doğrulama kuyruğu ve konum kontrolü (F5)")
public class AddressController {

    private final AddressService service;
    private final RecipientRepository recipients;
    private final UserRepository users;

    public AddressController(AddressService service, RecipientRepository recipients,
                             UserRepository users) {
        this.service = service;
        this.recipients = recipients;
        this.users = users;
    }

    @PostMapping("/validate")
    @Operation(summary = "Konumu kaydetmeden doğrula",
            description = """
                    Beş katmanın dördünü çalıştırır: geofence, yola snap, ters
                    geocoding, aykırı değer. Kabul edilmeyen nokta için de HTTP 200
                    döner — istek başarıyla değerlendirilmiştir; karar `acceptable`
                    alanındadır. Kaydetme denemesi 422 döner.
                    """)
    public ValidationResult validate(@Valid @RequestBody Coordinate body,
                                     @RequestParam(required = false) Long excludeAddressId) {
        return service.validate(body.lat(), body.lon(), excludeAddressId);
    }

    @GetMapping
    @Operation(summary = "Doğrulama kuyruğu",
            description = "Varsayılan: DOGRULAMA_BEKLIYOR durumundakiler.")
    public List<AddressResponse> queue(@RequestParam(required = false) VerificationStatus status) {
        return service.queue(status).stream().map(this::toResponse).toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Adres detayı")
    public AddressResponse get(@PathVariable Long id) {
        return toResponse(service.get(id));
    }

    @PostMapping("/{id}/verify")
    @Operation(summary = "Adresi onayla", description = "Beşinci katman — insan onayı.")
    public AddressResponse verify(@PathVariable Long id,
                                  @AuthenticationPrincipal AuthenticatedUser caller) {
        return toResponse(service.verify(id, caller.id()));
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Adresi reddet", description = "Gerekçe zorunludur.")
    public AddressResponse reject(@PathVariable Long id,
                                  @Valid @RequestBody RejectRequest body,
                                  @AuthenticationPrincipal AuthenticatedUser caller) {
        return toResponse(service.reject(id, caller.id(), body.reason()));
    }

    private AddressResponse toResponse(Address a) {
        String recipientName = recipients.findById(a.getRecipientId())
                .flatMap(r -> users.findById(r.getUserId()))
                .map(u -> u.getFullName())
                .orElse(null);

        return new AddressResponse(
                a.getId(), a.getRecipientId(), recipientName,
                a.getAddressText(), a.getDistrict(), a.getNeighbourhood(),
                Points.latOf(a.getLocation()), Points.lonOf(a.getLocation()),
                a.getSnappedNodeId(), a.getSnapDistanceM(),
                a.getVerificationStatus(), a.getRejectionReason(),
                a.getCreatedAt());
    }
}
