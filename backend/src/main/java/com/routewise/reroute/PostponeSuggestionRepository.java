package com.routewise.reroute;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostponeSuggestionRepository extends JpaRepository<PostponeSuggestion, Long> {

    Optional<PostponeSuggestion> findByStopIdAndStatus(Long stopId, PostponeSuggestion.Status status);

    List<PostponeSuggestion> findByRouteIdAndStatus(Long routeId, PostponeSuggestion.Status status);

    List<PostponeSuggestion> findByStatusAndCreatedAtGreaterThanEqualOrderByIdAsc(
            PostponeSuggestion.Status status, Instant since);

    /** Two dispatchers deciding the same suggestion: the second waits and sees the first one's decision. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM PostponeSuggestion s WHERE s.id = :id")
    Optional<PostponeSuggestion> lockById(@Param("id") Long id);
}
