package com.routewise.reroute;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param debounce             changes to one route closer together than this are
 *                             computed once; zero runs the reroute immediately, in
 *                             the caller's thread and transaction (tests)
 * @param maxPerRoutePerDay    beyond this many reroutes a day the courier's order is
 *                             left alone: removed stops still leave, nothing is reshuffled
 * @param suggestPostponeAbove an approved address move that makes the rest of the round
 *                             more expensive by more than this ratio (0.15 = 15 %) is
 *                             suggested to the company for postponing
 */
@ConfigurationProperties(prefix = "routewise.reroute")
public record RerouteProperties(Duration debounce, Integer maxPerRoutePerDay, Double suggestPostponeAbove) {

    public RerouteProperties {
        debounce = debounce != null ? debounce : Duration.ofMillis(500);
        maxPerRoutePerDay = maxPerRoutePerDay != null ? maxPerRoutePerDay : 10;
        suggestPostponeAbove = suggestPostponeAbove != null ? suggestPostponeAbove : 0.15;
    }
}
