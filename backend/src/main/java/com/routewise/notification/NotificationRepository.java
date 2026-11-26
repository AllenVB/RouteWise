package com.routewise.notification;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findTop50ByUserIdAndChannelOrderByIdDesc(Long userId, Notification.Channel channel);

    long countByUserIdAndChannelAndReadAtIsNull(Long userId, Notification.Channel channel);

    /** Only the caller's own rows change: other users' ids in the list are silently untouched. */
    @Modifying
    @Query("""
            UPDATE Notification n SET n.readAt = :at
            WHERE n.userId = :userId AND n.readAt IS NULL AND n.id IN :ids
            """)
    int markRead(@Param("userId") Long userId, @Param("ids") Collection<Long> ids, @Param("at") Instant at);

    @Modifying
    @Query("UPDATE Notification n SET n.readAt = :at WHERE n.userId = :userId AND n.readAt IS NULL")
    int markAllRead(@Param("userId") Long userId, @Param("at") Instant at);
}
