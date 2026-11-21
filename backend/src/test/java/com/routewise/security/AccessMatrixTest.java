package com.routewise.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.routewise.support.ApiTest;
import com.routewise.user.Role;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Every endpoint against every role, through the real filter chain.
 *
 * <p>The matrix is the effective rule, not only the filter's: the company passes
 * the {@code /courier/**} filter but closing a stop is refused in the controller,
 * so those two endpoints are listed as courier-only. Allowed calls use an id that
 * does not exist and an empty body, so they stop at 400/404 without side effects;
 * what is asserted is only that they were not refused for the role.
 */
class AccessMatrixTest extends ApiTest {

    private static final String API = "/api/v1";
    private static final String MISSING_ID = "999999";

    /** Who may call an endpoint; a public endpoint is open to every role and to anonymous callers. */
    enum Access {
        PUBLIC(true),
        ANY_SIGNED_IN(false, Role.values()),
        COMPANY(false, Role.COMPANY),
        RECIPIENT(false, Role.RECIPIENT),
        COURIER(false, Role.COURIER),
        COURIER_OR_COMPANY(false, Role.COURIER, Role.COMPANY),
        COMPANY_OR_RECIPIENT(false, Role.COMPANY, Role.RECIPIENT);

        final boolean anonymous;
        final Set<Role> roles;

        Access(boolean anonymous, Role... roles) {
            this.anonymous = anonymous;
            this.roles = roles.length == 0 ? EnumSet.allOf(Role.class) : EnumSet.copyOf(Arrays.asList(roles));
        }
    }

    record Endpoint(HttpMethod method, String path, Access access) {
        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    private static Endpoint ep(String method, String path, Access access) {
        return new Endpoint(HttpMethod.valueOf(method), API + path, access);
    }

    /** Mirrors {@code docs/api-contract.md} §6. A new endpoint must be added here. */
    static final List<Endpoint> MATRIX = List.of(
            ep("POST", "/auth/login", Access.PUBLIC),
            ep("POST", "/auth/refresh", Access.PUBLIC),
            ep("POST", "/auth/logout", Access.ANY_SIGNED_IN),
            ep("GET", "/auth/me", Access.ANY_SIGNED_IN),
            ep("GET", "/system/status", Access.ANY_SIGNED_IN),

            ep("GET", "/shipments", Access.COMPANY),
            ep("GET", "/shipments/{id}", Access.COMPANY),
            ep("POST", "/shipments", Access.COMPANY),
            ep("PATCH", "/shipments/{id}", Access.COMPANY),
            ep("DELETE", "/shipments/{id}", Access.COMPANY),
            ep("GET", "/recipients", Access.COMPANY),
            ep("GET", "/recipients/{id}/addresses", Access.COMPANY),
            ep("GET", "/addresses", Access.COMPANY),
            ep("GET", "/addresses/{id}", Access.COMPANY),
            ep("POST", "/addresses/{id}/verify", Access.COMPANY),
            ep("POST", "/addresses/{id}/reject", Access.COMPANY),
            ep("POST", "/addresses/validate", Access.COMPANY_OR_RECIPIENT),
            ep("GET", "/vehicles", Access.COMPANY),
            ep("GET", "/plans", Access.COMPANY),
            ep("POST", "/plans", Access.COMPANY),
            ep("POST", "/plans/{id}/optimize", Access.COMPANY),
            ep("POST", "/plans/{id}/dispatch", Access.COMPANY),
            ep("GET", "/plans/{id}/runs", Access.COMPANY),
            ep("GET", "/runs/{id}", Access.COMPANY),
            ep("GET", "/routes/{id}", Access.COMPANY),

            ep("GET", "/courier/route", Access.COURIER_OR_COMPANY),
            ep("GET", "/courier/route/changes", Access.COURIER_OR_COMPANY),
            ep("GET", "/reroute/suggestions", Access.COMPANY),
            ep("POST", "/compare/path", Access.COMPANY),
            ep("POST", "/plans/{id}/compare/tour", Access.COMPANY),
            ep("POST", "/compare/experiments", Access.COMPANY),
            ep("GET", "/compare/experiments", Access.COMPANY),
            ep("GET", "/compare/experiments/{id}", Access.COMPANY),
            ep("GET", "/comparisons/{id}", Access.COMPANY),
            ep("POST", "/reroute/suggestions/{id}/postpone", Access.COMPANY),
            ep("POST", "/reroute/suggestions/{id}/keep", Access.COMPANY),
            ep("GET", "/courier/stops/{id}", Access.COURIER_OR_COMPANY),
            ep("POST", "/courier/stops/{id}/deliver", Access.COURIER),
            ep("POST", "/courier/stops/{id}/fail", Access.COURIER),

            ep("GET", "/me/shipments", Access.RECIPIENT),
            ep("GET", "/me/shipments/{id}", Access.RECIPIENT),
            ep("POST", "/me/shipments/{id}/postpone", Access.RECIPIENT),
            ep("GET", "/me/addresses", Access.RECIPIENT),
            ep("POST", "/me/addresses", Access.RECIPIENT),
            ep("PUT", "/me/addresses/{id}", Access.RECIPIENT),
            ep("GET", "/me/notifications", Access.RECIPIENT),
            ep("POST", "/me/notifications/read", Access.RECIPIENT));

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @BeforeEach
    void seedOneUserPerRole() {
        user("firma@test", "COMPANY");
        courier(user("kurye@test", "COURIER"));
        recipient(user("alici@test", "RECIPIENT"));
    }

    private static String emailOf(Role role) {
        return switch (role) {
            case COMPANY -> "firma@test";
            case COURIER -> "kurye@test";
            case RECIPIENT -> "alici@test";
        };
    }

    private static MockHttpServletRequestBuilder call(Endpoint e) {
        var req = request(e.method(), e.path().replace("{id}", MISSING_ID));
        if (e.method() != HttpMethod.GET && e.method() != HttpMethod.DELETE) {
            req.contentType(MediaType.APPLICATION_JSON).content("{}");
        }
        return req;
    }

    private int status(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req).andReturn().getResponse().getStatus();
    }

    static Stream<Arguments> denied() {
        return MATRIX.stream().flatMap(e -> EnumSet.complementOf(EnumSet.copyOf(e.access().roles)).stream()
                .filter(r -> !e.access().anonymous)
                .map(r -> Arguments.of(e, r)));
    }

    static Stream<Arguments> allowed() {
        return MATRIX.stream().flatMap(e -> e.access().roles.stream().map(r -> Arguments.of(e, r)));
    }

    static Stream<Endpoint> protectedEndpoints() {
        return MATRIX.stream().filter(e -> !e.access().anonymous);
    }

    @ParameterizedTest(name = "{1} → {0} reddedilir (403)")
    @MethodSource("denied")
    @DisplayName("Yetkisiz rol 403 alır")
    void shouldForbidRoleOutsideMatrix(Endpoint e, Role role) throws Exception {
        assertThat(status(call(e).header("Authorization", bearer(emailOf(role))))).isEqualTo(403);
    }

    @ParameterizedTest(name = "{1} → {0} kabul edilir")
    @MethodSource("allowed")
    @DisplayName("Yetkili rol güvenlik katmanından geçer")
    void shouldLetAllowedRoleThrough(Endpoint e, Role role) throws Exception {
        assertThat(status(call(e).header("Authorization", bearer(emailOf(role)))))
                .as("%s için %s reddedilmemeli", e, role)
                .isNotIn(401, 403);
    }

    @ParameterizedTest(name = "anonim → {0} 401")
    @MethodSource("protectedEndpoints")
    @DisplayName("Oturumsuz istek 401 alır")
    void shouldRequireSignInOnProtectedEndpoint(Endpoint e) throws Exception {
        assertThat(status(call(e))).isEqualTo(401);
    }

    @ParameterizedTest(name = "{0} oturum olmadan çağrılabilir")
    @MethodSource("allowedAnonymous")
    @DisplayName("Açık uçlar oturum istemez")
    void shouldNotRequireSignInOnPublicEndpoint(Endpoint e) throws Exception {
        assertThat(status(call(e))).isNotIn(401, 403);
    }

    static Stream<Endpoint> allowedAnonymous() {
        return MATRIX.stream().filter(e -> e.access().anonymous);
    }

    @Test
    @DisplayName("Yenileme belirteci erişim belirteci yerine geçmez")
    void shouldRejectRefreshTokenAsBearer() throws Exception {
        var req = call(ep("GET", "/me/shipments", Access.RECIPIENT))
                .header("Authorization", refreshBearer("alici@test"));
        assertThat(status(req)).isEqualTo(401);
    }

    @Test
    @DisplayName("Başka anahtarla imzalanmış belirteç anonim sayılır")
    void shouldRejectTokenSignedWithAnotherKey() throws Exception {
        String forged = Jwts.builder()
                .subject("1").claim("role", "COMPANY").claim("typ", "access")
                .signWith(Keys.hmacShaKeyFor(new byte[32]))
                .compact();
        var req = call(ep("GET", "/shipments", Access.COMPANY)).header("Authorization", "Bearer " + forged);
        assertThat(status(req)).isEqualTo(401);
    }

    @Test
    @DisplayName("Matris uygulamadaki her ucu kapsar")
    void shouldCoverEveryApplicationEndpoint() {
        Set<String> live = mappings.getHandlerMethods().entrySet().stream()
                .filter(m -> m.getValue() instanceof HandlerMethod h
                        && h.getBeanType().getPackageName().startsWith("com.routewise"))
                .flatMap(m -> m.getKey().getMethodsCondition().getMethods().stream()
                        .flatMap(verb -> m.getKey().getPatternValues().stream().map(p -> verb + " " + p)))
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> listed = MATRIX.stream().map(Endpoint::toString)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(listed).isEqualTo(live);
    }
}
