package com.routewise.support;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The one database container of the test run, shared by every test base.
 *
 * <p>Started once and never stopped by hand; Ryuk removes it when the JVM exits.
 * A per-class {@code @Container} would be stopped after the first class while
 * Spring's cached context kept pointing at its port, failing every later class
 * with "could not open EntityManager".
 */
public final class PostgisContainer {

    /** The image the application runs on — PostGIS and pgRouting included. */
    public static final PostgreSQLContainer<?> INSTANCE = new PostgreSQLContainer<>(
            DockerImageName.parse("pgrouting/pgrouting:17-3.5-4.0.1")
                    .asCompatibleSubstituteFor("postgres"));

    static {
        INSTANCE.start();
    }

    private PostgisContainer() {}
}
