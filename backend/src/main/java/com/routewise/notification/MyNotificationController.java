package com.routewise.notification;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.routewise.auth.AuthenticatedUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/me/notifications")
@Tag(name = "Alıcı — bildirimler", description = "Uygulama içi bildirimler (A5)")
public class MyNotificationController {

    private final NotificationService service;

    public MyNotificationController(NotificationService service) {
        this.service = service;
    }

    /** Payload veridir; ekran türüne göre Türkçe metni kendisi kurar. */
    public record NotificationView(Long id, Notification.Type type, Long shipmentId,
                                   Map<String, Object> payload, Instant createdAt, boolean read) {}

    public record InboxView(List<NotificationView> items, long unreadCount) {}

    /** {@code {"ids": [...]}} ya da {@code {"all": true}} */
    public record ReadRequest(List<Long> ids, Boolean all) {}

    @GetMapping
    @Operation(summary = "Bildirimlerim", description = "Son 50, yeniden eskiye; okunmamış sayısıyla.")
    public InboxView inbox(@AuthenticationPrincipal AuthenticatedUser caller) {
        var inbox = service.inbox(caller.id());
        return new InboxView(inbox.items().stream()
                .map(n -> new NotificationView(n.getId(), n.getType(), n.getShipmentId(), n.getPayload(),
                        n.getCreatedAt(), n.getReadAt() != null))
                .toList(), inbox.unreadCount());
    }

    @PostMapping("/read")
    @Operation(summary = "Okundu işaretle", description = "Yalnızca kendi bildirimlerin değişir; başkasının kimliği yok sayılır.")
    public ResponseEntity<Void> read(@RequestBody ReadRequest body, @AuthenticationPrincipal AuthenticatedUser caller) {
        service.markRead(caller.id(), body.ids(), Boolean.TRUE.equals(body.all()));
        return ResponseEntity.noContent().build();
    }
}
