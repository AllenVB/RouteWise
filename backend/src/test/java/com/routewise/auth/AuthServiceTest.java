package com.routewise.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.routewise.common.error.ApiException;
import com.routewise.user.Role;
import com.routewise.user.User;
import com.routewise.user.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthServiceTest {

    private UserRepository users;
    private RefreshTokenRepository refreshTokens;
    private PasswordEncoder encoder;
    private JwtService jwt;
    private AuthService service;

    private User user;
    private final UUID jti = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        refreshTokens = mock(RefreshTokenRepository.class);
        encoder = mock(PasswordEncoder.class);
        jwt = mock(JwtService.class);

        var props = new JwtProperties("test-anahtari-en-az-otuziki-bayt-olmali!!",
                Duration.ofMinutes(15), Duration.ofDays(14), "routewise");
        service = new AuthService(users, refreshTokens, encoder, jwt, props);

        user = mock(User.class);
        when(user.getId()).thenReturn(7L);
        when(user.getEmail()).thenReturn("firma@routewise.local");
        when(user.getFullName()).thenReturn("Kargo Firması");
        when(user.getRole()).thenReturn(Role.COMPANY);
        when(user.isActive()).thenReturn(true);
        when(user.getPasswordHash()).thenReturn("$2b$10$hash");

        when(jwt.issueAccessToken(any())).thenReturn("access-token");
        when(jwt.issueRefreshToken(any())).thenReturn(
                new JwtService.IssuedRefreshToken("refresh-token", jti,
                        Instant.now().plus(Duration.ofDays(14))));
    }

    @Nested
    @DisplayName("Giriş")
    class Login {

        @Test
        @DisplayName("Doğru bilgiyle belirteç üretir ve yenileme kaydını saklar")
        void shouldIssueTokens() {
            when(users.findByEmailIgnoreCase("firma@routewise.local")).thenReturn(Optional.of(user));
            when(encoder.matches("dogru", "$2b$10$hash")).thenReturn(true);

            var res = service.login("firma@routewise.local", "dogru");

            assertThat(res.accessToken()).isEqualTo("access-token");
            assertThat(res.refreshToken()).isEqualTo("refresh-token");
            assertThat(res.role()).isEqualTo(Role.COMPANY);
            assertThat(res.displayName()).isEqualTo("Kargo Firması");
            assertThat(res.expiresInSeconds()).isEqualTo(900);

            var captor = ArgumentCaptor.forClass(RefreshToken.class);
            verify(refreshTokens).save(captor.capture());
            assertThat(captor.getValue().getJti()).isEqualTo(jti);
            assertThat(captor.getValue().getUserId()).isEqualTo(7L);
        }

        @Test
        @DisplayName("Yanlış parola ile bilinmeyen e-posta AYNI mesajı verir")
        void shouldNotLeakWhichEmailsExist() {
            when(users.findByEmailIgnoreCase("var@routewise.local")).thenReturn(Optional.of(user));
            when(encoder.matches(anyString(), anyString())).thenReturn(false);
            when(users.findByEmailIgnoreCase("yok@routewise.local")).thenReturn(Optional.empty());

            String yanlisParola = mesaj(() -> service.login("var@routewise.local", "yanlis"));
            String bilinmeyen = mesaj(() -> service.login("yok@routewise.local", "nese"));

            assertThat(yanlisParola).isEqualTo(bilinmeyen);
        }

        @Test
        @DisplayName("Başarısız girişte yenileme kaydı oluşmaz")
        void shouldNotPersistOnFailure() {
            when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.login("yok@routewise.local", "x"))
                    .isInstanceOf(ApiException.class);
            verify(refreshTokens, never()).save(any());
        }

        @Test
        @DisplayName("Kapalı hesap reddedilir")
        void shouldRejectInactive() {
            when(user.isActive()).thenReturn(false);
            when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(user));
            when(encoder.matches(anyString(), anyString())).thenReturn(true);

            assertThatThrownBy(() -> service.login("firma@routewise.local", "dogru"))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("kapalı");
        }
    }

    @Nested
    @DisplayName("Yenileme")
    class Refresh {

        @Test
        @DisplayName("Eski belirteci geçersizler — çalınan belirteç ikinci kez işe yaramaz")
        void shouldRotate() {
            var stored = new RefreshToken(jti, 7L, Instant.now().plus(Duration.ofDays(1)));
            when(jwt.parseRefreshToken("eski")).thenReturn(
                    new JwtService.ParsedToken(7L, null, null, jti));
            when(refreshTokens.findByJti(jti)).thenReturn(Optional.of(stored));
            when(users.findById(7L)).thenReturn(Optional.of(user));

            service.refresh("eski");

            assertThat(stored.getRevokedAt()).isNotNull();
            assertThat(stored.isUsable()).isFalse();
        }

        @Test
        @DisplayName("Geçersizlenmiş belirteç reddedilir")
        void shouldRejectRevoked() {
            var stored = new RefreshToken(jti, 7L, Instant.now().plus(Duration.ofDays(1)));
            stored.revoke();
            when(jwt.parseRefreshToken("eski")).thenReturn(
                    new JwtService.ParsedToken(7L, null, null, jti));
            when(refreshTokens.findByJti(jti)).thenReturn(Optional.of(stored));

            assertThatThrownBy(() -> service.refresh("eski"))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("sona ermiş");
        }

        @Test
        @DisplayName("Süresi dolmuş kayıt reddedilir")
        void shouldRejectExpiredRecord() {
            var stored = new RefreshToken(jti, 7L, Instant.now().minusSeconds(60));
            when(jwt.parseRefreshToken("eski")).thenReturn(
                    new JwtService.ParsedToken(7L, null, null, jti));
            when(refreshTokens.findByJti(jti)).thenReturn(Optional.of(stored));

            assertThatThrownBy(() -> service.refresh("eski"))
                    .isInstanceOf(ApiException.class);
        }

        @Test
        @DisplayName("Veritabanında kaydı olmayan belirteç reddedilir")
        void shouldRejectUnknownJti() {
            when(jwt.parseRefreshToken("eski")).thenReturn(
                    new JwtService.ParsedToken(7L, null, null, jti));
            when(refreshTokens.findByJti(jti)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.refresh("eski"))
                    .isInstanceOf(ApiException.class);
        }
    }

    @Nested
    @DisplayName("Çıkış")
    class Logout {

        @Test
        @DisplayName("Belirteci gerçekten geçersizler")
        void shouldRevoke() {
            var stored = new RefreshToken(jti, 7L, Instant.now().plus(Duration.ofDays(1)));
            when(jwt.parseRefreshToken("t")).thenReturn(
                    new JwtService.ParsedToken(7L, null, null, jti));
            when(refreshTokens.findByJti(jti)).thenReturn(Optional.of(stored));

            service.logout("t");

            assertThat(stored.isUsable()).isFalse();
        }

        @Test
        @DisplayName("Geçersiz belirteçle çağrılsa bile hata vermez")
        void shouldBeIdempotent() {
            when(jwt.parseRefreshToken("cop")).thenThrow(
                    new ApiException(com.routewise.common.error.ErrorCode.UNAUTHENTICATED, "x"));

            service.logout("cop");   // istisna fırlatmamalı
        }
    }

    private static String mesaj(Runnable r) {
        try {
            r.run();
            throw new AssertionError("istisna bekleniyordu");
        } catch (ApiException e) {
            return e.getMessage();
        }
    }
}
