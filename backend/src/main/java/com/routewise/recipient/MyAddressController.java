package com.routewise.recipient;

import java.util.List;

import jakarta.validation.Valid;

import com.routewise.address.Address;
import com.routewise.address.AddressDtos.CreateAddressRequest;
import com.routewise.auth.AuthenticatedUser;
import com.routewise.common.geo.Points;
import com.routewise.recipient.RecipientDtos.RecipientAddress;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/me/addresses")
@Tag(name = "Alıcı — adreslerim", description = "Alıcının kendi adresleri (A4)")
public class MyAddressController {

    private final MyAddressService service;

    public MyAddressController(MyAddressService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Adreslerim")
    public List<RecipientAddress> list(@AuthenticationPrincipal AuthenticatedUser caller) {
        Long defaultId = service.recipientOf(caller.id()).getDefaultAddressId();
        return service.list(caller.id()).stream().map(a -> toResponse(a, defaultId)).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Yeni adres",
            description = "Konum dört otomatik kontrolden geçer; kabul edilirse firma onayı bekler. "
                    + "Sınır dışı veya yoldan uzak nokta 422 ile reddedilir ve kaydedilmez.")
    public RecipientAddress create(@Valid @RequestBody CreateAddressRequest body,
                                   @AuthenticationPrincipal AuthenticatedUser caller) {
        return toResponse(service.create(caller.id(), body), null);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Adresi güncelle",
            description = "Doğrulama sıfırlanır, adres yeniden firma onayına düşer. "
                    + "Günlük değişiklik limiti dolunca 409 CHANGE_LIMIT_REACHED.")
    public RecipientAddress relocate(@PathVariable Long id,
                                     @Valid @RequestBody CreateAddressRequest body,
                                     @AuthenticationPrincipal AuthenticatedUser caller) {
        return toResponse(service.relocate(caller.id(), id, body), null);
    }

    private static RecipientAddress toResponse(Address a, Long defaultId) {
        return new RecipientAddress(
                a.getId(), a.getAddressText(), a.getDistrict(), a.getNeighbourhood(),
                Points.latOf(a.getLocation()), Points.lonOf(a.getLocation()),
                a.getVerificationStatus(),
                a.getVerificationStatus().isUsableForRouting(),
                a.getId().equals(defaultId));
    }
}
