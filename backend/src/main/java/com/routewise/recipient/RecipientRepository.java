package com.routewise.recipient;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecipientRepository extends JpaRepository<Recipient, Long> {

    Optional<Recipient> findByUserId(Long userId);

    /**
     * Search by name, e-mail or phone, case-insensitively.
     *
     * <p>{@code q} is never null: an empty string matches everyone. A nullable
     * parameter would need {@code :q IS NULL}, which PostgreSQL rejects with
     * "could not determine data type of parameter".
     */
    @Query(value = """
            SELECT new com.routewise.recipient.RecipientSummary(
                       r.id, u.fullName, u.email, u.phone, r.defaultAddressId)
            FROM Recipient r JOIN User u ON u.id = r.userId
            WHERE u.active = true
              AND (LOWER(u.fullName) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(u.email) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR COALESCE(u.phone, '') LIKE CONCAT('%', :q, '%'))
            ORDER BY u.fullName ASC, r.id ASC
            """,
            countQuery = """
            SELECT COUNT(r)
            FROM Recipient r JOIN User u ON u.id = r.userId
            WHERE u.active = true
              AND (LOWER(u.fullName) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(u.email) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR COALESCE(u.phone, '') LIKE CONCAT('%', :q, '%'))
            """)
    Page<RecipientSummary> search(@Param("q") String q, Pageable pageable);
}
