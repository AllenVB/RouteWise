package com.routewise.auth;

import jakarta.validation.Valid;

import com.routewise.auth.AuthDtos.LoginRequest;
import com.routewise.auth.AuthDtos.MeResponse;
import com.routewise.auth.AuthDtos.RefreshRequest;
import com.routewise.auth.AuthDtos.TokenResponse;
import com.routewise.common.error.ApiException;
import com.routewise.user.UserRepository;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@Tag(name = "Kimlik", description = "Giriş, yenileme, çıkış")
public class AuthController {

    private final AuthService authService;
    private final UserRepository users;

    public AuthController(AuthService authService, UserRepository users) {
        this.authService = authService;
        this.users = users;
    }

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(summary = "Giriş yap")
    public TokenResponse login(@Valid @RequestBody LoginRequest req) {
        return authService.login(req.email(), req.password());
    }

    @PostMapping("/refresh")
    @SecurityRequirements
    @Operation(summary = "Belirteçleri yenile",
            description = "Eski yenileme belirteci bu çağrıyla geçersizlenir.")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest req) {
        return authService.refresh(req.refreshToken());
    }

    @PostMapping("/logout")
    @Operation(summary = "Çıkış yap", description = "Yenileme belirtecini geçersizler.")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest req) {
        authService.logout(req.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    @Operation(summary = "Oturum sahibinin bilgileri")
    public MeResponse me(@AuthenticationPrincipal AuthenticatedUser caller) {
        return users.findById(caller.id())
                .map(u -> new MeResponse(u.getId(), u.getEmail(), u.getFullName(),
                        u.getPhone(), u.getRole()))
                .orElseThrow(() -> ApiException.notFound("Hesap"));
    }
}
