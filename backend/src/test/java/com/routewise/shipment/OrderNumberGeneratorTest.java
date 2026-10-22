package com.routewise.shipment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderNumberGeneratorTest {

    @Test
    @DisplayName("Biçim: BND-YYYY-NNNNN")
    void shouldFormatWithFiveDigits() {
        assertThat(OrderNumberGenerator.format(2026, 417)).isEqualTo("BND-2026-00417");
    }

    @Test
    @DisplayName("Küçük sayılar sıfırla doldurulur")
    void shouldPadSmallCounters() {
        assertThat(OrderNumberGenerator.format(2026, 1)).isEqualTo("BND-2026-00001");
    }

    @Test
    @DisplayName("Beş haneyi aşan sayaç kırpılmaz")
    void shouldNotTruncateLargeCounters() {
        // Sayaç yıl başında sıfırlanmaz; 99.999'u aşınca numara uzar ama
        // benzersizliğini korur. Kırpmak çakışma üretirdi.
        assertThat(OrderNumberGenerator.format(2026, 123456)).isEqualTo("BND-2026-123456");
    }

    @Test
    @DisplayName("Yıl numaraya yansır")
    void shouldCarryYear() {
        assertThat(OrderNumberGenerator.format(2027, 5)).startsWith("BND-2027-");
    }
}
