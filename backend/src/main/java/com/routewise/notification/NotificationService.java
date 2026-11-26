package com.routewise.notification;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.routewise.notification.Notification.Channel;
import com.routewise.notification.Notification.Type;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Notifications to recipients (A5): in-app rows written in the caller's
 * transaction, e-mail sent only after it commits.
 *
 * <p>In the same transaction on purpose: if dispatch or verification rolls back,
 * the user must not be told it happened. E-mail goes out after commit for the
 * same reason, and a mail failure never undoes the business change.
 */
@Service
public class NotificationService {

    private final NotificationRepository notifications;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public NotificationService(NotificationRepository notifications, ApplicationEventPublisher events, Clock clock) {
        this.notifications = notifications;
        this.events = events;
        this.clock = clock;
    }

    /** Published after the in-app row is written; {@link EmailNotifier} picks it up after commit. */
    public record Created(Long notificationId, Long userId, Long shipmentId, Type type, Map<String, Object> payload) {}

    public record Inbox(List<Notification> items, long unreadCount) {}

    @Transactional
    public Notification notify(Long userId, Long shipmentId, Type type, Map<String, Object> payload) {
        Instant now = clock.instant();
        Notification n = notifications.save(new Notification(userId, shipmentId, type, Channel.IN_APP, payload, now));
        events.publishEvent(new Created(n.getId(), userId, shipmentId, type, payload));
        return n;
    }

    @Transactional(readOnly = true)
    public Inbox inbox(Long userId) {
        return new Inbox(
                notifications.findTop50ByUserIdAndChannelOrderByIdDesc(userId, Channel.IN_APP),
                notifications.countByUserIdAndChannelAndReadAtIsNull(userId, Channel.IN_APP));
    }

    /** @param ids null or empty with {@code all=true} marks everything read */
    @Transactional
    public int markRead(Long userId, List<Long> ids, boolean all) {
        Instant now = clock.instant();
        if (all) {
            return notifications.markAllRead(userId, now);
        }
        return ids == null || ids.isEmpty() ? 0 : notifications.markRead(userId, ids, now);
    }
}
