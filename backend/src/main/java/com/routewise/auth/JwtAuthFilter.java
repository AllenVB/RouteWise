package com.routewise.auth;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.routewise.common.error.ApiException;

import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads the bearer token and populates the security context.
 *
 * <p>A bad token is not rejected here: the filter simply leaves the request
 * anonymous and lets the authorization rules decide. That keeps public endpoints
 * reachable even when a stale token is sent.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(PREFIX)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                JwtService.ParsedToken t = jwtService.parseAccessToken(header.substring(PREFIX.length()));
                AuthenticatedUser principal =
                        new AuthenticatedUser(t.userId(), t.email(), t.role());
                var auth = new UsernamePasswordAuthenticationToken(
                        principal, null,
                        List.of(new SimpleGrantedAuthority(t.role().authority())));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (ApiException e) {
                logger.debug("gecersiz erisim belirteci: " + e.getMessage());
            }
        }

        chain.doFilter(request, response);
    }
}
