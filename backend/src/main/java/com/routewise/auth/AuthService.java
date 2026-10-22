package com.routewise.auth;

import java.util.UUID;

import com.routewise.auth.AuthDtos.TokenResponse;
import com.routewise.common.error.ApiException;
import com.routewise.common.error.ErrorCode;
import com.routewise.user.User;
import com.routewise.user.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final JwtProperties props;

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens,
                       PasswordEncoder encoder, JwtService jwt, JwtProperties props) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.encoder = encoder;
        this.jwt = jwt;
        this.props = props;
    }

    @Transactional
    public TokenResponse login(String email, String rawPassword) {
        User user = users.findByEmailIgnoreCase(email).orElse(null);

        // Kullanıcı yok ile parola yanlış aynı yanıtı verir: hangi e-postanın
        // kayıtlı olduğunu sızdırmamak için.
        if (user == null || !encoder.matches(rawPassword, user.getPasswordHash())) {
            log.info("basarisiz giris denemesi: {}", email);
            throw new ApiException(ErrorCode.UNAUTHENTICATED,
                    "E-posta veya parola hatalı.");
        }
        if (!user.isActive()) {
            throw new ApiException(ErrorCode.FORBIDDEN,
                    "Hesabın kapalı. Firma yetkilisiyle görüş.");
        }
        return issue(user);
    }

    @Transactional
    public TokenResponse refresh(String refreshTokenValue) {
        JwtService.ParsedToken parsed = jwt.parseRefreshToken(refreshTokenValue);

        RefreshToken stored = refreshTokens.findByJti(parsed.jti())
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED,
                        "Oturum bilgisi geçersiz. Tekrar giriş yap."));

        if (!stored.isUsable()) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED,
                    "Oturumun sona ermiş. Tekrar giriş yap.");
        }

        User user = users.findById(stored.getUserId())
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED,
                        "Hesap bulunamadı."));

        // Dönüşüm: eski belirteç kullanılır kullanılmaz geçersizlenir, böylece
        // çalınmış bir yenileme belirteci ikinci kez işe yaramaz.
        stored.revoke();
        return issue(user);
    }

    @Transactional
    public void logout(String refreshTokenValue) {
        try {
            UUID jti = jwt.parseRefreshToken(refreshTokenValue).jti();
            refreshTokens.findByJti(jti).ifPresent(RefreshToken::revoke);
        } catch (ApiException e) {
            // Çıkış hiçbir zaman hata vermez: belirteç zaten geçersizse istenen
            // sonuç gerçekleşmiş demektir.
            log.debug("cikista gecersiz belirtec: {}", e.getMessage());
        }
    }

    private TokenResponse issue(User user) {
        String access = jwt.issueAccessToken(user);
        JwtService.IssuedRefreshToken refresh = jwt.issueRefreshToken(user);

        refreshTokens.save(new RefreshToken(refresh.jti(), user.getId(), refresh.expiresAt()));

        return new TokenResponse(
                access,
                refresh.token(),
                props.accessTtl().toSeconds(),
                user.getRole(),
                user.getFullName());
    }
}
