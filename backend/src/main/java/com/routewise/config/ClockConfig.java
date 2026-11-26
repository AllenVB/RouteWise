package com.routewise.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The single time source, so tests can pin it.
 *
 * <p>Business days follow the depot's local time, not the container's: a server
 * in UTC would roll "today" over at 03:00 Istanbul time, resetting daily limits
 * and order-number years three hours late.
 */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock(@Value("${routewise.time-zone:Europe/Istanbul}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }
}
