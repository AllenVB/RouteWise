package com.routewise.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.routewise.notification.Notification.Channel;
import com.routewise.notification.Notification.Type;
import com.routewise.user.User;
import com.routewise.user.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class NotificationServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-03T06:00:00Z"), ZoneId.of("Europe/Istanbul"));

    private final NotificationRepository repo = mock(NotificationRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final NotificationService service = new NotificationService(repo, events, CLOCK);

    @BeforeEach
    void setUp() {
        when(repo.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Nested
    @DisplayName("notify")
    class Notify {

        @Test
        @DisplayName("Uygulama içi satır yazılır ve e-posta için olay yayınlanır")
        void writesInAppAndPublishes() {
            service.notify(3L, 51L, Type.ETA, Map.of("orderNumber", "BND-2026-00051"));

            var saved = ArgumentCaptor.forClass(Notification.class);
            verify(repo).save(saved.capture());
            assertThat(saved.getValue().getChannel()).isEqualTo(Channel.IN_APP);
            assertThat(saved.getValue().getSentAt()).isEqualTo(CLOCK.instant());
            verify(events).publishEvent(any(NotificationService.Created.class));
        }
    }

    @Nested
    @DisplayName("markRead")
    class MarkRead {

        @Test
        @DisplayName("Boş liste hiçbir şeyi değiştirmez — 'tümü' ancak açıkça istenirse")
        void emptyIdsChangeNothing() {
            assertThat(service.markRead(3L, List.of(), false)).isZero();
            verify(repo, never()).markAllRead(anyLong(), any());
        }

        @Test
        @DisplayName("all=true kullanıcının tüm okunmamışlarını kapatır")
        void allMarksEverything() {
            service.markRead(3L, null, true);
            verify(repo).markAllRead(3L, CLOCK.instant());
        }

        @Test
        @DisplayName("Kimlikler kullanıcının kendisiyle sınırlı sorguya gider")
        void idsScopedToUser() {
            service.markRead(3L, List.of(7L, 8L), false);
            verify(repo).markRead(3L, List.of(7L, 8L), CLOCK.instant());
        }
    }

    @Nested
    @DisplayName("EmailNotifier")
    class Email {

        private final MailPort mail = mock(MailPort.class);
        private final UserRepository users = mock(UserRepository.class);
        private final EmailNotifier notifier = new EmailNotifier(mail, users, repo, CLOCK);

        private final NotificationService.Created eta = new NotificationService.Created(
                1L, 3L, 51L, Type.ETA, Map.of("orderNumber", "BND-2026-00051", "eta", "2026-11-03T05:25:00Z"));

        @BeforeEach
        void user() {
            User u = mock(User.class);
            when(u.getEmail()).thenReturn("alici@ornek.com");
            when(users.findById(3L)).thenReturn(Optional.of(u));
        }

        @Test
        @DisplayName("Gönderilemeyen e-posta için EMAIL satırı yazılmaz — tablo yalan söylemez")
        void noRowWhenNotSent() {
            when(mail.send(anyString(), anyString(), anyString())).thenReturn(false);

            notifier.onCreated(eta);

            verify(repo, never()).save(any());
        }

        @Test
        @DisplayName("Gönderilen e-posta EMAIL satırı olarak kaydedilir")
        void rowWhenSent() {
            when(mail.send(anyString(), anyString(), anyString())).thenReturn(true);

            notifier.onCreated(eta);

            var saved = ArgumentCaptor.forClass(Notification.class);
            verify(repo).save(saved.capture());
            assertThat(saved.getValue().getChannel()).isEqualTo(Channel.EMAIL);
        }

        @Test
        @DisplayName("SMTP hatası sevki bozmaz: yakalanır, satır yazılmaz")
        void mailFailureIsContained() {
            when(mail.send(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("smtp kapalı"));

            notifier.onCreated(eta);

            verify(repo, never()).save(any());
        }

        @Test
        @DisplayName("ETA metni İstanbul saatiyle: 05:25Z = 08:25")
        void etaRenderedInIstanbulTime() {
            when(mail.send(anyString(), anyString(), anyString())).thenReturn(true);

            notifier.onCreated(eta);

            verify(mail).send(eq("alici@ornek.com"), eq("Kargonuz bugün yola çıkıyor"),
                    eq("BND-2026-00051 numaralı kargonuzun tahmini teslim saati 08:25."));
        }

        @Test
        @DisplayName("Ret e-postası sebebi taşır")
        void rejectionCarriesReason() {
            var rejected = new NotificationService.Created(2L, 3L, null, Type.DOGRULAMA,
                    Map.of("addressText", "Yeni Mah.", "result", "REDDEDILDI", "reason", "Yanlış mahalle"));

            String[] text = notifier.render(rejected);

            assertThat(text[0]).isEqualTo("Adresiniz onaylanmadı");
            assertThat(text[1]).contains("Yanlış mahalle");
        }
    }
}
