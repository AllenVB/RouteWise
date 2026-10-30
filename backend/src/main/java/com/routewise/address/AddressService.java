package com.routewise.address;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import com.routewise.address.AddressDtos.CreateAddressRequest;
import com.routewise.address.AddressDtos.ValidationResult;
import com.routewise.common.error.ApiException;
import com.routewise.common.error.ErrorCode;
import com.routewise.common.geo.Points;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AddressService {

    private static final Logger log = LoggerFactory.getLogger(AddressService.class);

    private final AddressRepository addresses;
    private final AddressValidator validator;
    private final AddressValidationProperties props;
    private final Clock clock;

    public AddressService(AddressRepository addresses, AddressValidator validator,
                          AddressValidationProperties props, Clock clock) {
        this.addresses = addresses;
        this.validator = validator;
        this.props = props;
        this.clock = clock;
    }

    /** Kaydetmeden değerlendirme — haritaya tıklandığı anda geri bildirim için. */
    @Transactional(readOnly = true)
    public ValidationResult validate(double lat, double lon, Long excludeAddressId) {
        return validator.validate(lat, lon, excludeAddressId);
    }

    @Transactional(readOnly = true)
    public List<Address> queue(VerificationStatus status) {
        VerificationStatus wanted = status == null
                ? VerificationStatus.DOGRULAMA_BEKLIYOR : status;
        return addresses.findByVerificationStatusOrderByCreatedAtAsc(wanted);
    }

    @Transactional(readOnly = true)
    public Address get(Long id) {
        return addresses.findById(id).orElseThrow(() -> ApiException.notFound("Adres"));
    }

    @Transactional
    public Address create(Long recipientId, CreateAddressRequest req) {
        var loc = req.location();
        ValidationResult result = validator.validate(loc.lat(), loc.lon(), null);
        requireAcceptable(result);

        Address address = new Address(
                recipientId,
                req.addressText(),
                req.district(),
                req.neighbourhood(),
                Points.of(loc.lat(), loc.lon()),
                result.snappedNodeId(),
                round(result.snapDistanceM()));

        Address saved = addresses.save(address);
        log.info("adres olusturuldu: {} (alici {}) — dogrulama bekliyor",
                saved.getId(), recipientId);
        return saved;
    }

    @Transactional
    public Address relocate(Long addressId, CreateAddressRequest req) {
        Address address = get(addressId);
        LocalDate today = LocalDate.now(clock);

        if (address.changesOn(today) >= props.maxChangesPerDay()) {
            throw new ApiException(ErrorCode.CHANGE_LIMIT_REACHED,
                    "Bugün için adres değiştirme hakkın doldu. Yarın tekrar deneyebilirsin.");
        }

        var loc = req.location();
        ValidationResult result = validator.validate(loc.lat(), loc.lon(), addressId);
        requireAcceptable(result);

        address.relocate(
                req.addressText(), req.district(), req.neighbourhood(),
                Points.of(loc.lat(), loc.lon()),
                result.snappedNodeId(), round(result.snapDistanceM()), today);

        log.info("adres {} tasindi — dogrulama durumu sifirlandi", addressId);
        return address;
    }

    @Transactional
    public Address verify(Long addressId, Long verifierUserId) {
        Address address = get(addressId);

        if (address.getVerificationStatus() == VerificationStatus.DOGRULANDI) {
            return address;   // zaten onaylı; tekrar onaylamak hata değil
        }
        address.verify(verifierUserId);
        log.info("adres {} onaylandi (kullanici {})", addressId, verifierUserId);
        return address;
    }

    @Transactional
    public Address reject(Long addressId, Long verifierUserId, String reason) {
        Address address = get(addressId);
        address.reject(verifierUserId, reason);
        log.info("adres {} reddedildi: {}", addressId, reason);
        return address;
    }

    /**
     * Engelleyici bulgu varsa kaydetmeyi durdurur.
     *
     * <p>Değerlendirme ucu 200 döner çünkü istek başarıyla işlenmiştir; kaydetme
     * denemesi ise 422 döner. Hatalı nokta veritabanına hiç girmez.
     */
    private static void requireAcceptable(ValidationResult result) {
        if (result.acceptable()) {
            return;
        }
        ErrorCode code = result.withinServiceArea()
                ? ErrorCode.TOO_FAR_FROM_ROAD
                : ErrorCode.OUTSIDE_SERVICE_AREA;
        throw new ApiException(code, String.join(" ", result.blockers()));
    }

    private static BigDecimal round(Double meters) {
        return meters == null ? null : BigDecimal.valueOf(meters).setScale(2, RoundingMode.HALF_UP);
    }
}
