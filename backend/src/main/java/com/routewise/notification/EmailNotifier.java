package com.routewise.notification;

import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import com.routewise.notification.Notification.Channel;
import com.routewise.user.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends the e-mail copy of a notification once the business change has committed.
 *
 * <p>An EMAIL row is written only when the message actually left, so the table
 * never claims a delivery that did not happen. Failures are logged and
 * swallowed deliberately: the in-app notification already exists and the
 * dispatch must not be undone by an SMTP outage.
 */
@Component
public class EmailNotifier {

    private static final Logger log = LoggerFactory.getLogger(EmailNotifier.class);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private final MailPort mail;
    private final UserRepository users;
    private final NotificationRepository notifications;
    private final Clock clock;

    public EmailNotifier(MailPort mail, UserRepository users, NotificationRepository notifications, Clock clock) {
        this.mail = mail;
        this.users = users;
        this.notifications = notifications;
        this.clock = clock;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCreated(NotificationService.Created e) {
        users.findById(e.userId()).ifPresent(user -> {
            String[] text = render(e);
            try {
                if (mail.send(user.getEmail(), text[0], text[1])) {
                    Instant now = clock.instant();
                    notifications.save(new Notification(e.userId(), e.shipmentId(), e.type(), Channel.EMAIL,
                            e.payload(), now));
                }
            } catch (RuntimeException ex) {
                log.warn("bildirim {} e-postasi gonderilemedi: {}", e.notificationId(), ex.getMessage());
            }
        });
    }

    /** [konu, gövde] — ekrandaki metinle aynı anlamı taşır. */
    String[] render(NotificationService.Created e) {
        Object order = e.payload().getOrDefault("orderNumber", "");
        return switch (e.type()) {
            case ETA -> Boolean.TRUE.equals(e.payload().get("updated"))
                    ? new String[] {"Tahmini teslim saatiniz değişti",
                            "Kuryenin rotası güncellendi; %s numaralı kargonuzun yeni tahmini teslim saati %s."
                                    .formatted(order, clockOf(e.payload().get("eta")))}
                    : new String[] {"Kargonuz bugün yola çıkıyor",
                            "%s numaralı kargonuzun tahmini teslim saati %s."
                                    .formatted(order, clockOf(e.payload().get("eta")))};
            case DOGRULAMA -> "DOGRULANDI".equals(e.payload().get("result"))
                    ? new String[] {"Adresiniz onaylandı",
                            "\"%s\" adresiniz onaylandı; kargolarınız bu adrese planlanabilir."
                                    .formatted(e.payload().get("addressText"))}
                    : new String[] {"Adresiniz onaylanmadı",
                            "\"%s\" adresiniz onaylanmadı. Sebep: %s. Konumu düzeltip yeniden gönderebilirsiniz."
                                    .formatted(e.payload().get("addressText"), e.payload().get("reason"))};
            case ERTELEME -> new String[] {"Kargonuz ertelendi", postponed(order, e.payload())};
            // Kuryeye giden yükte rota kimliği var; alıcıya gidende yok.
            case ROTA_DEGISTI -> e.payload().containsKey("routeId")
                    ? new String[] {"Rotanız güncellendi",
                            "%s numaralı kargo rotanızdan çıktı. Kalan durak: %s. Sırayı uygulamadan kontrol edin."
                                    .formatted(order, e.payload().get("openStops"))}
                    : new String[] {"Teslimat planınız değişti",
                            "%s numaralı kargonuzun teslim planı değişti.".formatted(order)};
        };
    }

    private static String postponed(Object order, Map<String, Object> payload) {
        Object day = payload.get("postponedTo");
        String when = day == null ? "" : " " + day + " tarihine";
        String why = "EXCEEDS_WORKDAY".equals(payload.get("reason"))
                ? " Kurye bugün mesai içinde adresinize ulaşamayacaktı."
                : "";
        return "%s numaralı kargonuz%s ertelendi.%s".formatted(order, when, why);
    }

    private String clockOf(Object iso) {
        if (iso == null) {
            return "belirlenecek";
        }
        return ZonedDateTime.ofInstant(Instant.parse(iso.toString()), clock.getZone()).format(CLOCK);
    }
}
