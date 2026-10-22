package com.routewise.auth;

import com.routewise.user.Role;

/**
 * The caller, as established by the access token.
 *
 * <p>Injected into controllers with {@code @AuthenticationPrincipal}. Holds no
 * mutable state and never the password hash.
 */
public record AuthenticatedUser(Long id, String email, Role role) {

    public boolean isCompany() {
        return role == Role.COMPANY;
    }
}
