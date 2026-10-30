package com.routewise.shipment;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShipmentRepository
        extends JpaRepository<Shipment, Long>, JpaSpecificationExecutor<Shipment> {

    Optional<Shipment> findByOrderNumber(String orderNumber);

    boolean existsByAddressId(Long addressId);

    /**
     * Shipments a plan for {@code date} may route: every waiting one, plus those
     * postponed to that day or earlier.
     *
     * <p>Ordered by creation: this order <b>is</b> the naive baseline's visiting
     * order, so it must be stable and meaningful, not whatever the database returns.
     * Address verification is checked by the caller, which reports exclusions.
     */
    @Query("""
            SELECT s FROM Shipment s
            WHERE s.status = com.routewise.shipment.ShipmentStatus.BEKLEMEDE
               OR (s.status = com.routewise.shipment.ShipmentStatus.ERTELENDI
                   AND s.postponedTo <= :date)
            ORDER BY s.createdAt ASC, s.id ASC
            """)
    List<Shipment> findPlannableOn(@Param("date") LocalDate date);
}
