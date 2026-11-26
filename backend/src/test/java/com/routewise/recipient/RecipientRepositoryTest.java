package com.routewise.recipient;

import static org.assertj.core.api.Assertions.assertThat;

import com.routewise.support.PostgisRepositoryTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

class RecipientRepositoryTest extends PostgisRepositoryTest {

    @Autowired
    private RecipientRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        recipient("elif@ornek.com", "Elif Demir", "05321112233", true);
        recipient("ahmet@ornek.com", "Ahmet Yılmaz", null, true);
        recipient("zeynep@ornek.com", "Zeynep Kaya", "05559998877", true);
        recipient("eski@ornek.com", "Elif Pasif", "05000000000", false);
        // Alıcı profili olmayan firma kullanıcısı aramada çıkmamalı
        jdbc.update("INSERT INTO users (email, password_hash, full_name, role) "
                + "VALUES ('firma@ornek.com', 'x', 'Elif Firma', 'COMPANY')");
    }

    private void recipient(String email, String name, String phone, boolean active) {
        jdbc.update("INSERT INTO users (email, password_hash, full_name, phone, role, is_active) "
                + "VALUES (?, 'x', ?, ?, 'RECIPIENT', ?)", email, name, phone, active);
        jdbc.update("INSERT INTO recipients (user_id) SELECT id FROM users WHERE email = ?", email);
    }

    private Page<RecipientSummary> search(String q) {
        return repository.search(q, PageRequest.of(0, 25));
    }

    @Test
    @DisplayName("Boş metin tüm aktif alıcıları ada göre sıralı döndürür")
    void emptyQueryReturnsAllActiveSortedByName() {
        assertThat(search("").getContent())
                .extracting(RecipientSummary::fullName)
                .containsExactly("Ahmet Yılmaz", "Elif Demir", "Zeynep Kaya");
    }

    @Test
    @DisplayName("Ad büyük/küçük harf duyarsız eşleşir; pasif kullanıcı ve firma çıkmaz")
    void matchesNameCaseInsensitively() {
        assertThat(search("elif").getContent())
                .extracting(RecipientSummary::fullName)
                .containsExactly("Elif Demir");
    }

    @Test
    @DisplayName("E-posta ve telefonla da bulunur; telefonu olmayan alıcı sorguyu bozmaz")
    void matchesEmailAndPhone() {
        assertThat(search("zeynep@").getContent()).extracting(RecipientSummary::fullName)
                .containsExactly("Zeynep Kaya");
        assertThat(search("1112233").getContent()).extracting(RecipientSummary::fullName)
                .containsExactly("Elif Demir");
    }

    @Test
    @DisplayName("Sayfalama sayımı aynı filtreyi kullanır")
    void countQueryUsesSameFilter() {
        Page<RecipientSummary> page = repository.search("", PageRequest.of(0, 2));

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getTotalPages()).isEqualTo(2);
    }
}
