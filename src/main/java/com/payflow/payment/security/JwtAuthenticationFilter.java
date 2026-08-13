package com.payflow.payment.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns an {@code Authorization: Bearer <jwt>} header into an authenticated {@code
 * SecurityContext} for the duration of one request.
 *
 * <p>Ported from wallet-service's filter of the same name; see that class for the full
 * rationale. In short: the entire authentication decision is a local signature check, there
 * is no database or network call, and a missing or unusable token is not itself an error —
 * the context is simply left empty and the authorisation rules in {@code SecurityConfig}
 * decide whether that matters for the endpoint being called.
 *
 * @see JwtTokenValidator for exactly which claims are checked
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";

    /** Spring Security's convention: an authority backing {@code hasRole("X")} is "ROLE_X". */
    private static final String ROLE_PREFIX = "ROLE_";

    private final JwtTokenValidator tokenValidator;

    public JwtAuthenticationFilter(JwtTokenValidator tokenValidator) {
        this.tokenValidator = tokenValidator;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String token = extractBearerToken(request);

        if (token == null || SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            authenticate(request, tokenValidator.validate(token));
        } catch (Exception ex) {
            log.debug("Bearer token rejected on {}: {}",
                    request.getRequestURI(), ex.getMessage());
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, AuthenticatedUser caller) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(caller, null, authorities(caller));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private List<GrantedAuthority> authorities(AuthenticatedUser caller) {
        if (caller.role() == null || caller.role().isBlank()) {
            return List.of();
        }
        return List.of(new SimpleGrantedAuthority(ROLE_PREFIX + caller.role()));
    }

    /**
     * @return the raw JWT, or null when the header is absent or not a bearer credential
     */
    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
