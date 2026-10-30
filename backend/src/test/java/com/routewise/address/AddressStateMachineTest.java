package com.routewise.address;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

import com.routewise.common.geo.Points;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The address state machine — the rule the whole verification feature exists for.
 */
class AddressStateMachineTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 30);

    private static Address newAddress() {
        return new Address(12L, "Yeni Mahalle, 1204 Sk. No:7", "Bandırma", "Yeni Mahalle",
                Points.of(40.3521, 27.9767), 48219L, new BigDecimal("7.40"));
    }

    @Test
    @DisplayName("Yeni adres doğrudan kuyruğa girer, taslakta beklemez")
    void shouldStartPendingVerification() {
        assertThat(newAddress().getVerificationStatus())
                .isEqualTo(VerificationStatus.DOGRULAMA_BEKLIYOR);
    }

    @Test
    @DisplayName("Yalnızca DOGRULANDI rotaya girebilir")
    void onlyVerifiedIsRoutable() {
        assertThat(VerificationStatus.DOGRULANDI.isUsableForRouting()).isTrue();
        assertThat(VerificationStatus.TASLAK.isUsableForRouting()).isFalse();
        assertThat(VerificationStatus.DOGRULAMA_BEKLIYOR.isUsableForRouting()).isFalse();
        assertThat(VerificationStatus.REDDEDILDI.isUsableForRouting()).isFalse();
    }

    @Test
    @DisplayName("Onay, onaylayanı ve zamanı kaydeder")
    void verifyRecordsWho() {
        Address a = newAddress();

        a.verify(1L);

        assertThat(a.getVerificationStatus()).isEqualTo(VerificationStatus.DOGRULANDI);
        assertThat(a.getVerifiedBy()).isEqualTo(1L);
        assertThat(a.getVerifiedAt()).isNotNull();
    }

    @Test
    @DisplayName("Ret, gerekçeyi saklar")
    void rejectKeepsReason() {
        Address a = newAddress();

        a.reject(1L, "Nokta yola 142 m uzakta.");

        assertThat(a.getVerificationStatus()).isEqualTo(VerificationStatus.REDDEDILDI);
        assertThat(a.getRejectionReason()).isEqualTo("Nokta yola 142 m uzakta.");
    }

    @Test
    @DisplayName("DOĞRULANMIŞ adres taşınınca onay DÜŞER — projenin en sessiz hata kaynağı")
    void relocateResetsVerification() {
        Address a = newAddress();
        a.verify(1L);
        assertThat(a.getVerificationStatus()).isEqualTo(VerificationStatus.DOGRULANDI);

        a.relocate("Çınarlı Mah., 290 Sk. No:6", "Bandırma", "Çınarlı",
                Points.of(40.3488, 27.9712), 51004L, new BigDecimal("12.00"), DAY);

        // Bu sıfırlama olmazsa firma onayladığını sanırken rota başka noktaya çizilir.
        assertThat(a.getVerificationStatus()).isEqualTo(VerificationStatus.DOGRULAMA_BEKLIYOR);
        assertThat(a.getVerifiedBy()).isNull();
        assertThat(a.getVerifiedAt()).isNull();
        assertThat(a.getLocation().getY()).isEqualTo(40.3488);
    }

    @Test
    @DisplayName("Taşıma günlük sayacı artırır — limit kontrolü buna dayanır")
    void relocateIncrementsDailyCounter() {
        Address a = newAddress();
        assertThat(a.changesOn(DAY)).isZero();

        a.relocate("A", null, null, Points.of(40.35, 27.97), 1L, BigDecimal.ONE, DAY);
        a.relocate("B", null, null, Points.of(40.36, 27.98), 2L, BigDecimal.ONE, DAY);

        assertThat(a.changesOn(DAY)).isEqualTo(2);
    }

    @Test
    @DisplayName("Dünkü değişiklikler bugünün limitinden düşmez — sayaç gün dönümünde sıfırlanır")
    void dailyCounterResetsOnNewDay() {
        Address a = newAddress();
        a.relocate("A", null, null, Points.of(40.35, 27.97), 1L, BigDecimal.ONE, DAY);
        a.relocate("B", null, null, Points.of(40.36, 27.98), 2L, BigDecimal.ONE, DAY);

        LocalDate tomorrow = DAY.plusDays(1);
        assertThat(a.changesOn(tomorrow)).isZero();

        a.relocate("C", null, null, Points.of(40.37, 27.99), 3L, BigDecimal.ONE, tomorrow);

        // 3 değil 1: dünkü iki değişiklik bugüne taşınmaz.
        assertThat(a.changesOn(tomorrow)).isEqualTo(1);
        assertThat(a.changesOn(DAY)).isZero();
    }

    @Test
    @DisplayName("Reddedilen adres taşınınca gerekçe temizlenir")
    void relocateClearsRejectionReason() {
        Address a = newAddress();
        a.reject(1L, "Yanlış mahalle.");

        a.relocate("Düzeltildi", null, null, Points.of(40.35, 27.97), 1L, BigDecimal.ONE, DAY);

        assertThat(a.getRejectionReason()).isNull();
        assertThat(a.getVerificationStatus()).isEqualTo(VerificationStatus.DOGRULAMA_BEKLIYOR);
    }

    @Test
    @DisplayName("Enlem/boylam sırası karışmaz")
    void pointUsesLatLonOrder() {
        // JTS (x,y) = (boylam, enlem) ister; Points bu çevirmeyi tek yerde yapar.
        var p = Points.of(40.3521, 27.9767);

        assertThat(Points.latOf(p)).isEqualTo(40.3521);
        assertThat(Points.lonOf(p)).isEqualTo(27.9767);
        assertThat(p.getSRID()).isEqualTo(4326);
    }
}
