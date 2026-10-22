package com.routewise.shipment;

/**
 * Lifecycle of a shipment.
 *
 * <p>Names match the {@code ck_shipments_status} check constraint; the database
 * rejects anything else, so adding a value here needs a migration too.
 */
public enum ShipmentStatus {

    BEKLEMEDE,
    PLANLANDI,
    YOLDA,
    TESLIM_EDILDI,
    ERTELENDI;

    /** Once a courier is carrying it, the shipment is no longer editable. */
    public boolean isEditable() {
        return this == BEKLEMEDE || this == PLANLANDI || this == ERTELENDI;
    }

    /** Only an unplanned shipment can be removed without disturbing a route. */
    public boolean isDeletable() {
        return this == BEKLEMEDE || this == ERTELENDI;
    }
}
