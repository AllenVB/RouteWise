package com.routewise.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT settings.
 *
 * @param secret     HMAC key; must be at least 32 bytes. Supplied per environment,
 *                   never hard-coded.
 * @param accessTtl  short on purpose — a leaked access token stops working quickly
 * @param refreshTtl long, but revocable (see {@link RefreshToken})
 * @param issuer     recorded in the token so foreign tokens are rejected
 */
@ConfigurationProperties(prefix = "routewise.jwt")
public record JwtProperties(
        String secret,
        Duration accessTtl,
        Duration refreshTtl,
        String issuer) {

    public JwtProperties {
        if (secret == null || secret.getBytes().length < 32) {
            throw new IllegalStateException(
                    "routewise.jwt.secret en az 32 bayt olmalı (HS256 gereği).");
        }
        accessTtl = accessTtl != null ? accessTtl : Duration.ofMinutes(15);
        refreshTtl = refreshTtl != null ? refreshTtl : Duration.ofDays(14);
        issuer = issuer != null ? issuer : "routewise";
    }
}
