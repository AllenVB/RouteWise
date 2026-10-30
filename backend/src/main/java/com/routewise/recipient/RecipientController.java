package com.routewise.recipient;

import java.util.List;

import com.routewise.address.Address;
import com.routewise.common.PageResponse;
import com.routewise.common.geo.Points;
import com.routewise.recipient.RecipientDtos.RecipientAddress;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/recipients")
@Tag(name = "Alıcı", description = "Yeni kargo formu için alıcı ve adres seçimi (F4)")
public class RecipientController {

    private final RecipientService service;

    public RecipientController(RecipientService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Alıcı ara",
            description = "Ad, e-posta veya telefonda geçen metin; boşsa tüm alıcılar. Ada göre sıralı.")
    public PageResponse<RecipientSummary> search(@RequestParam(required = false) String q,
                                                 @PageableDefault(size = 25) Pageable pageable) {
        return PageResponse.of(
                service.search(q, pageable.getPageNumber(), pageable.getPageSize()), r -> r);
    }

    @GetMapping("/{id}/addresses")
    @Operation(summary = "Alıcının adresleri",
            description = "Doğrulama durumuyla; yalnızca usableForRouting=true olanlarla kargo oluşturulabilir.")
    public List<RecipientAddress> addresses(@PathVariable Long id) {
        Long defaultId = service.get(id).getDefaultAddressId();
        return service.addressesOf(id).stream().map(a -> toResponse(a, defaultId)).toList();
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
