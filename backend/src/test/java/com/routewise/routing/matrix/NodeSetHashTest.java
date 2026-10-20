package com.routewise.routing.matrix;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NodeSetHashTest {

    @Test
    @DisplayName("Python ile aynı değeri üretir — iki taraf aynı sabiti sınar")
    void shouldMatchPythonForKnownSet() {
        // sha256("1,2,3") — app/matrix/builder.py bu değeri üretmek zorunda.
        // İki taraf da bu sabiti sınar; biri değişirse cache sessizce ıskalardı.
        assertThat(NodeSetHash.of(List.of(1L, 2L, 3L)))
                .isEqualTo("8a6ae15122001229edb8866f56e342af12ae8187203c3e3b33931743e7c0c48d");
    }

    @Test
    @DisplayName("Sıra hash'i değiştirmez")
    void shouldBeOrderIndependent() {
        assertThat(NodeSetHash.of(List.of(3L, 1L, 2L)))
                .isEqualTo(NodeSetHash.of(List.of(1L, 2L, 3L)));
    }

    @Test
    @DisplayName("Tekrar eden düğüm hash'i değiştirmez")
    void shouldBeDuplicateIndependent() {
        assertThat(NodeSetHash.of(List.of(1L, 2L, 2L, 3L)))
                .isEqualTo(NodeSetHash.of(List.of(1L, 2L, 3L)));
    }

    @Test
    @DisplayName("Farklı küme farklı hash üretir")
    void shouldDifferForDifferentSets() {
        assertThat(NodeSetHash.of(List.of(1L, 2L, 3L)))
                .isNotEqualTo(NodeSetHash.of(List.of(1L, 2L, 4L)));
    }

    @Test
    @DisplayName("Biçim: 64 karakter küçük harf hex")
    void shouldBeLowercaseHex() {
        String h = NodeSetHash.of(List.of(42L));
        assertThat(h).hasSize(64).isLowerCase().matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("Boş liste reddedilir")
    void shouldRejectEmpty() {
        assertThatThrownBy(() -> NodeSetHash.of(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("boş");
    }
}
