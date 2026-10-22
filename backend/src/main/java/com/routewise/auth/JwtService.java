package com.routewise.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import com.routewise.common.error.ApiException;
import com.routewise.common.error.ErrorCode;
import com.routewise.user.Role;
import com.routewise.user.User;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import org.springframework.stereotype.Service;

/**
 * Issues and verifies JWTs.
 *
 * <p>Access tokens carry the role so authorization needs no database round trip.
 * The role is still re-checked on the server for every request — the token says
 * who the caller claims to be, not what they may do.
 */
@Service
public class JwtService {

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_TYPE = "typ";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final JwtProperties props;
    private final SecretKey key;

    public JwtService(JwtProperties props) {
        this.props = props;
        this.key = Keys.hmacShaKeyFor(props.secret().getBytes(StandardCharsets.UTF_8));
    }

    public String issueAccessToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(props.issuer())
                .subject(String.valueOf(user.getId()))
                .claim(CLAIM_EMAIL, user.getEmail())
                .claim(CLAIM_ROLE, user.getRole().name())
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(props.accessTtl())))
                .signWith(key)
                .compact();
    }

    /** @return the token and the jti that must be stored so it can be revoked */
    public IssuedRefreshToken issueRefreshToken(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plus(props.refreshTtl());
        UUID jti = UUID.randomUUID();

        String token = Jwts.builder()
                .issuer(props.issuer())
                .subject(String.valueOf(user.getId()))
                .id(jti.toString())
                .claim(CLAIM_TYPE, TYPE_REFRESH)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key)
                .compact();

        return new IssuedRefreshToken(token, jti, expiry);
    }

    public ParsedToken parseAccessToken(String token) {
        return parse(token, TYPE_ACCESS);
    }

    public ParsedToken parseRefreshToken(String token) {
        return parse(token, TYPE_REFRESH);
    }

    private ParsedToken parse(String token, String expectedType) {
        Claims c;
        try {
            c = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(props.issuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED,
                    "Oturumun süresi doldu. Tekrar giriş yap.", e);
        } catch (JwtException | IllegalArgumentException e) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED,
                    "Oturum bilgisi geçersiz. Tekrar giriş yap.", e);
        }

        // Yenileme belirteci erişim belirteci yerine kullanılamaz, tersi de.
        if (!expectedType.equals(c.get(CLAIM_TYPE, String.class))) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Yanlış türde oturum bilgisi.");
        }

        Long userId = Long.valueOf(c.getSubject());
        String roleName = c.get(CLAIM_ROLE, String.class);
        Role role = roleName == null ? null : Role.valueOf(roleName);
        UUID jti = c.getId() == null ? null : UUID.fromString(c.getId());

        return new ParsedToken(userId, c.get(CLAIM_EMAIL, String.class), role, jti);
    }

    public record IssuedRefreshToken(String token, UUID jti, Instant expiresAt) {}

    public record ParsedToken(Long userId, String email, Role role, UUID jti) {}
}
