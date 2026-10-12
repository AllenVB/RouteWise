package com.routewise.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * HTTP client for the Python optimizer service.
 *
 * <p>Timeouts are deliberate: simulated annealing can take seconds, so the read
 * timeout is generous, but a hung connection must not block a request thread
 * indefinitely.
 */
@Configuration
public class RestClientConfig {

    @Bean
    RestClient optimizerClient(RestClient.Builder builder,
                               @Value("${routewise.optimizer.base-url}") String baseUrl) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(60));

        return builder
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("Accept", "application/json")
                .build();
    }
}
