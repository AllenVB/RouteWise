package com.routewise;

import static org.assertj.core.api.Assertions.assertThat;

import com.routewise.optimization.client.OptimizerClient;
import com.routewise.support.PostgisContainer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The whole application starts: every bean wires, the full changelog applies.
 *
 * <p>Unit tests build their objects by hand and cannot see wiring faults. This
 * test exists because one shipped: two beans named {@code optimizerClient}, all
 * unit tests green, and the application refusing to start.
 */
@SpringBootTest
@TestPropertySource(properties = "spring.liquibase.contexts=test")
class ApplicationContextTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> DB = PostgisContainer.INSTANCE;

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("Uygulama context'i ayağa kalkar ve optimizer istemcisi bağlanır")
    void contextLoads() {
        assertThat(context.getBean(OptimizerClient.class)).isNotNull();
    }
}
