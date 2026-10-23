package com.routewise.address;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Thresholds for address validation.
 *
 * @param maxSnapDistanceM  beyond this the vehicle cannot reach the point, so it
 *                          is rejected outright
 * @param outlierDistanceM  a point this far from every other delivery is probably
 *                          a misclick — a warning, not a rejection
 * @param maxChangesPerDay  how often a recipient may move their own address
 */
@ConfigurationProperties(prefix = "routewise.address")
public record AddressValidationProperties(
        Double maxSnapDistanceM,
        Double outlierDistanceM,
        Integer maxChangesPerDay) {

    public AddressValidationProperties {
        maxSnapDistanceM = maxSnapDistanceM != null ? maxSnapDistanceM : 50.0;
        outlierDistanceM = outlierDistanceM != null ? outlierDistanceM : 5000.0;
        maxChangesPerDay = maxChangesPerDay != null ? maxChangesPerDay : 2;
    }
}
