package com.routewise.user;

/**
 * The three roles in the system.
 *
 * <p>Names match the {@code ck_users_role} check constraint in the schema; adding
 * one here without a migration would be rejected by the database.
 */
public enum Role {

    /** Kargo firması — veri sahibi, doğrulama ve planlama yetkisi. */
    COMPANY,

    /** Kurye — yalnızca kendi rotası. */
    COURIER,

    /** Alıcı — yalnızca kendi kargoları ve adresi. */
    RECIPIENT;

    /** Spring Security authority adı. */
    public String authority() {
        return "ROLE_" + name();
    }
}
