package com.routewise.support;

import com.routewise.auth.JwtService;
import com.routewise.user.UserRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for tests that go through HTTP: the real security filter chain, real JWTs
 * and the real database.
 *
 * <p>Each test runs in a transaction that is rolled back, so rows inserted here
 * never reach other test classes sharing the container. MockMvc runs on the test
 * thread, so the services join that same transaction.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
// Yeniden rotalama testte eşzamanlıdır: test transaction'ı hiç commit edilmez,
// commit sonrası bekleyen bir iş hiç koşmazdı.
@TestPropertySource(properties = {"spring.liquibase.contexts=test", "routewise.reroute.debounce=0s"})
public abstract class ApiTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> DB = PostgisContainer.INSTANCE;

    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;

    /** @return the user id */
    protected long user(String email, String role) {
        return jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, full_name, role)
                VALUES (?, 'x', ?, ?) RETURNING id
                """, Long.class, email, "Test " + email, role);
    }

    /** @return the recipient id */
    protected long recipient(long userId) {
        return jdbc.queryForObject("INSERT INTO recipients (user_id) VALUES (?) RETURNING id",
                Long.class, userId);
    }

    /** @return the courier id */
    protected long courier(long userId) {
        return jdbc.queryForObject("INSERT INTO couriers (user_id) VALUES (?) RETURNING id",
                Long.class, userId);
    }

    protected String bearer(String email) {
        return "Bearer " + jwt.issueAccessToken(users.findByEmailIgnoreCase(email).orElseThrow());
    }

    protected String refreshBearer(String email) {
        return "Bearer " + jwt.issueRefreshToken(users.findByEmailIgnoreCase(email).orElseThrow()).token();
    }
}
