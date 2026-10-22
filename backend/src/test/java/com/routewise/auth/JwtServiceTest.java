package com.routewise.auth;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.routewise.common.error.ApiException;
import com.routewise.user.Role;
import com.routewise.user.User;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = "test-anahtari-en-az-otuziki-bayt-olmali!!";

    private JwtService jwt;
    private User user;

    private static JwtProperties props(Duration accessTtl) {
        return new JwtProperties(SECRET, accessTtl, Duration.ofDays(14), "routewise");
    }

    @BeforeEach
    void setUp() {
        jwt = new JwtService(props(Duration.ofMinutes(15)));
        user = mock(User.class);
        when(user.getId()).thenReturn(7L);
        when(user.getEmail()).thenReturn("firma@routewise.local");
        when(user.getRole()).thenReturn(Role.COMPANY);
    }

    @Nested
    @DisplayName("Erişim belirteci")
    class AccessToken {

        @Test
        @DisplayName("Kullanıcı, e-posta ve rolü taşır")
        void shouldCarryIdentity() {
            var parsed = jwt.parseAccessToken(jwt.issueAccessToken(user));

            assertThat(parsed.userId()).isEqualTo(7L);
            assertThat(parsed.email()).isEqualTo("firma@routewise.local");
            assertThat(parsed.role()).isEqualTo(Role.COMPANY);
        }

        @Test
        @DisplayName("Süresi dolmuşsa reddedilir")
        void shouldRejectExpired() {
            var expired = new JwtService(props(Duration.ofSeconds(-10)));

            assertThatThrownBy(() -> jwt.parseAccessToken(expired.issueAccessToken(user)))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("süresi doldu");
        }

        @Test
        @DisplayName("Başka anahtarla imzalanmışsa reddedilir")
        void shouldRejectForeignSignature() {
            var yabanci = new JwtService(
                    new JwtProperties("baska-bir-anahtar-otuziki-bayttan-uzun!!",
                            Duration.ofMinutes(15), Duration.ofDays(14), "routewise"));

            assertThatThrownBy(() -> jwt.parseAccessToken(yabanci.issueAccessToken(user)))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("geçersiz");
        }

        @Test
        @DisplayName("Başka issuer'dan gelmişse reddedilir")
        void shouldRejectForeignIssuer() {
            var yabanci = new JwtService(
                    new JwtProperties(SECRET, Duration.ofMinutes(15),
                            Duration.ofDays(14), "baska-sistem"));

            assertThatThrownBy(() -> jwt.parseAccessToken(yabanci.issueAccessToken(user)))
                    .isInstanceOf(ApiException.class);
        }

        @Test
        @DisplayName("Bozulmuş belirteç reddedilir")
        void shouldRejectTampered() {
            String token = jwt.issueAccessToken(user);
            String bozuk = token.substring(0, token.length() - 3) + "xyz";

            assertThatThrownBy(() -> jwt.parseAccessToken(bozuk))
                    .isInstanceOf(ApiException.class);
        }
    }

    @Nested
    @DisplayName("Yenileme belirteci")
    class RefreshToken {

        @Test
        @DisplayName("Geçersizlenebilmesi için jti taşır")
        void shouldCarryJti() {
            var issued = jwt.issueRefreshToken(user);

            assertThat(issued.jti()).isNotNull();
            assertThat(jwt.parseRefreshToken(issued.token()).jti()).isEqualTo(issued.jti());
        }

        @Test
        @DisplayName("Her çağrıda farklı jti üretir")
        void shouldBeUniquePerIssue() {
            assertThat(jwt.issueRefreshToken(user).jti())
                    .isNotEqualTo(jwt.issueRefreshToken(user).jti());
        }
    }

    @Nested
    @DisplayName("Tür karışması")
    class TokenTypeConfusion {

        @Test
        @DisplayName("Yenileme belirteci erişim belirteci yerine kullanılamaz")
        void refreshIsNotAccess() {
            String refresh = jwt.issueRefreshToken(user).token();

            assertThatThrownBy(() -> jwt.parseAccessToken(refresh))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("Yanlış türde");
        }

        @Test
        @DisplayName("Erişim belirteci yenileme yerine kullanılamaz")
        void accessIsNotRefresh() {
            String access = jwt.issueAccessToken(user);

            assertThatThrownBy(() -> jwt.parseRefreshToken(access))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("Yanlış türde");
        }
    }

    @Test
    @DisplayName("Kısa anahtar kabul edilmez")
    void shouldRejectShortSecret() {
        assertThatThrownBy(() -> new JwtProperties("kisa", Duration.ofMinutes(15),
                Duration.ofDays(14), "routewise"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bayt");
    }
}
