package com.routewise.recipient;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RecipientRepository extends JpaRepository<Recipient, Long> {

    Optional<Recipient> findByUserId(Long userId);
}
