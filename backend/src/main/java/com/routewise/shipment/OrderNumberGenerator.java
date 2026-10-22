package com.routewise.shipment;

import java.time.Clock;
import java.time.LocalDate;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Component;

/**
 * Produces order numbers in the form {@code BND-YYYY-NNNNN}.
 *
 * <p>The counter is a database sequence, not a {@code MAX(order_number)+1} query:
 * two dispatchers creating a shipment at the same moment must never receive the
 * same number, and a sequence is the only way to guarantee that without locking.
 *
 * <p>BND is the Bandırma branch; the year makes the number readable at a glance.
 * The sequence does not reset each year — uniqueness matters more than a tidy
 * counter, and a gap says nothing about lost parcels.
 */
@Component
public class OrderNumberGenerator {

    static final String PREFIX = "BND";

    private final EntityManager em;
    private final Clock clock;

    /**
     * Single constructor on purpose: with two, Spring cannot choose and falls
     * back to looking for a no-arg one, which fails only at startup.
     */
    public OrderNumberGenerator(EntityManager em, Clock clock) {
        this.em = em;
        this.clock = clock;
    }

    public String next() {
        Number seq = (Number) em
                .createNativeQuery("SELECT nextval('shipment_number_seq')")
                .getSingleResult();
        return format(LocalDate.now(clock).getYear(), seq.longValue());
    }

    static String format(int year, long counter) {
        return "%s-%d-%05d".formatted(PREFIX, year, counter);
    }
}
