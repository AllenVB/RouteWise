package com.routewise.shipment;

import org.springframework.data.jpa.domain.Specification;

/**
 * Filters for the shipment listing (F3).
 *
 * <p>Built as specifications rather than a JPQL query with {@code :param IS NULL}
 * branches. With a null parameter PostgreSQL cannot infer the type and the
 * driver sends {@code bytea}, so {@code lower(:q)} fails at runtime with
 * "function lower(bytea) does not exist" — a query that compiles, passes unit
 * tests with mocks, and breaks only against the real database.
 */
final class ShipmentSpecifications {

    private ShipmentSpecifications() {}

    static Specification<Shipment> hasStatus(ShipmentStatus status) {
        return status == null
                ? null
                : (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    static Specification<Shipment> orderNumberContains(String q) {
        if (q == null) {
            return null;
        }
        String pattern = "%" + q.toLowerCase() + "%";
        return (root, query, cb) -> cb.like(cb.lower(root.get("orderNumber")), pattern);
    }

    static Specification<Shipment> filter(ShipmentStatus status, String q) {
        return Specification.allOf(hasStatus(status), orderNumberContains(q));
    }
}
