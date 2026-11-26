package com.routewise.support;

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for repository tests against a real PostgreSQL + PostGIS + pgRouting.
 *
 * <p>Uses the very image the application runs on, so native PostGIS queries and
 * the Liquibase changelog are exercised exactly as in production — an in-memory
 * database would accept SQL that PostgreSQL rejects, and has no PostGIS at all.
 *
 * <p>The {@code test} Liquibase context skips the seed: each test inserts the
 * rows it reasons about, so results never depend on demo data.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.liquibase.contexts=test")
public abstract class PostgisRepositoryTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> DB = PostgisContainer.INSTANCE;
}
