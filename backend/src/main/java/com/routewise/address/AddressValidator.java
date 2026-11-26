package com.routewise.address;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.routewise.address.AddressDtos.ValidationResult;

import org.springframework.stereotype.Service;

/**
 * The four automated layers of address validation; the fifth is the company's
 * own approval (see {@link AddressService}).
 *
 * <pre>
 * 1. Geofence        — nokta Bandırma sınırları içinde mi?      ENGEL
 * 2. Yola snap       — en yakın yola uzaklık eşiğin altında mı? ENGEL
 * 3. Ters geocoding  — hangi sokak? kullanıcıya gösterilir      BİLGİ
 * 4. Aykırı değer    — diğer teslimatlardan anormal uzak mı?    UYARI
 * </pre>
 *
 * <p>Layer 2 does double duty: a point that cannot be snapped to a road is also
 * a point no route can reach, so the check the user sees as "wrong pin" is the
 * same check the optimizer needs.
 */
@Service
public class AddressValidator {

    /**
     * User-facing numbers are formatted in Turkish explicitly.
     *
     * <p>Without a locale, {@code String.formatted} follows the JVM default, so
     * the same message reads "11,0 km" on a developer's machine and "11.0 km"
     * inside the container. A message shown to a Turkish user must not depend on
     * where the process happens to run.
     */
    private static final Locale TR = Locale.of("tr", "TR");

    private final ServiceAreaRepository geo;
    private final AddressValidationProperties props;

    public AddressValidator(ServiceAreaRepository geo, AddressValidationProperties props) {
        this.geo = geo;
        this.props = props;
    }

    public ValidationResult validate(double lat, double lon, Long excludeAddressId) {
        List<String> warnings = new ArrayList<>();
        List<String> blockers = new ArrayList<>();

        // 1 — geofence
        boolean inside = geo.containsPoint(lon, lat);
        if (!inside) {
            blockers.add("Seçilen nokta Bandırma sınırları dışında.");
        }

        // 2 — yola snap
        Long nodeId = null;
        Double snapM = null;
        ServiceAreaRepository.SnapResult snap = geo.nearestRoadNode(lon, lat);
        if (snap == null) {
            blockers.add("Yol ağı yüklenmemiş. Veri hattı çalıştırılmalı.");
        } else {
            nodeId = snap.nodeId();
            snapM = snap.distanceM();
            if (snapM > props.maxSnapDistanceM()) {
                blockers.add(String.format(TR,
                        "Nokta en yakın yola %.0f m uzakta; araç buraya ulaşamaz.", snapM));
            }
        }

        // 3 — ters geocoding (bilgi amaçlı, engel değil)
        String street = inside ? geo.nearestStreetName(lon, lat) : null;

        // 4 — aykırı değer
        Double nearest = geo.distanceToNearestVerifiedAddress(lon, lat, excludeAddressId);
        boolean outlier = nearest != null && nearest > props.outlierDistanceM();
        if (outlier) {
            warnings.add(String.format(TR,
                    "Bu nokta diğer teslimatlardan %.1f km uzakta. Doğru mu?", nearest / 1000));
        }

        return new ValidationResult(
                inside, nodeId, snapM, street, nearest, outlier,
                blockers.isEmpty(), List.copyOf(warnings), List.copyOf(blockers));
    }
}
