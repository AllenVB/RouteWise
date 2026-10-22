package com.routewise.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import com.routewise.user.Role;

/** Kimlik uçlarının istek ve yanıt gövdeleri. */
public final class AuthDtos {

    private AuthDtos() {}

    public record LoginRequest(
            @NotBlank(message = "E-posta gerekli.")
            @Email(message = "Geçerli bir e-posta gir.")
            String email,

            @NotBlank(message = "Parola gerekli.")
            String password) {}

    public record RefreshRequest(
            @NotBlank(message = "Yenileme bilgisi gerekli.")
            String refreshToken) {}

    public record TokenResponse(
            String accessToken,
            String refreshToken,
            long expiresInSeconds,
            Role role,
            String displayName) {}

    public record MeResponse(
            Long id,
            String email,
            String fullName,
            String phone,
            Role role) {}
}
