package com.routewise.address;

/**
 * Address verification state machine.
 *
 * <pre>
 * TASLAK -> DOGRULAMA_BEKLIYOR -> DOGRULANDI
 *                  ^                   |
 *                  +-------------------+   (alıcı adresi değiştirince)
 *                  -> REDDEDILDI
 * </pre>
 *
 * <p>Only {@link #DOGRULANDI} may enter a delivery plan. That single rule is why
 * the state exists: without it the company would believe it had approved an
 * address while a route was drawn to a different one.
 */
public enum VerificationStatus {

    TASLAK,
    DOGRULAMA_BEKLIYOR,
    DOGRULANDI,
    REDDEDILDI;

    public boolean isUsableForRouting() {
        return this == DOGRULANDI;
    }
}
