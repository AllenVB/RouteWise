package com.routewise.plan;

/** Names match {@code ck_plans_status}. */
public enum PlanStatus {

    TASLAK,
    SEVK_EDILDI,
    TAMAMLANDI;

    /** Once couriers have their routes, re-optimizing would move them under their feet. */
    public boolean isOptimizable() {
        return this == TASLAK;
    }
}
