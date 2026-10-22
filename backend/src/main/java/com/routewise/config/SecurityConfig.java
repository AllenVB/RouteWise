package com.routewise.config;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.routewise.auth.JwtAuthFilter;
import com.routewise.common.error.ApiError;
import com.routewise.common.error.ErrorCode;
import com.routewise.user.Role;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Authorization rules, mirroring the matrix in {@code docs/api-contract.md} §6.
 *
 * <p>Scoped rules ("only your own records") cannot be expressed here; they are
 * enforced in the services, which return 404 rather than 403 so the existence of
 * someone else's record is not leaked.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String API = "/api/v1";

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter,
                                    ObjectMapper mapper) throws Exception {
        http
            // Durumsuz JWT: oturum yok, dolayısıyla CSRF saldırı yüzeyi de yok.
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, API + "/auth/login", API + "/auth/refresh")
                    .permitAll()
                .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**")
                    .permitAll()

                .requestMatchers(API + "/me/**").hasRole(Role.RECIPIENT.name())
                .requestMatchers(API + "/courier/**")
                    .hasAnyRole(Role.COURIER.name(), Role.COMPANY.name())
                .requestMatchers(API + "/addresses/validate")
                    .hasAnyRole(Role.COMPANY.name(), Role.RECIPIENT.name())
                .requestMatchers(API + "/system/**").authenticated()
                .requestMatchers(API + "/auth/**").authenticated()

                // Kalan her şey firma yetkisindedir.
                .requestMatchers(API + "/**").hasRole(Role.COMPANY.name())
                .anyRequest().authenticated())
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> write(mapper, res,
                        ErrorCode.UNAUTHENTICATED,
                        "Bu işlem için giriş yapmalısın."))
                .accessDeniedHandler((req, res, ex) -> write(mapper, res,
                        ErrorCode.FORBIDDEN,
                        "Bu işlem için yetkin yok.")))
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /** Hata gövdesi her yerde aynı olmalı: güvenlik katmanı da {@link ApiError} döner. */
    private static void write(ObjectMapper mapper, HttpServletResponse res,
                              ErrorCode code, String message) throws IOException {
        res.setStatus(code.status().value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        mapper.writeValue(res.getWriter(), ApiError.of(code, message, "-"));
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
