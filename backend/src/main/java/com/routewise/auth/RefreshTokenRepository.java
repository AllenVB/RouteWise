package com.routewise.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByJti(UUID jti);

    List<RefreshToken> findByUserIdAndRevokedAtIsNull(Long userId);
}
