package com.finmates.social.config.security;

import java.io.IOException;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Servlet report-only logger for the azp guard. Register it AFTER the Spring Security filter chain
 * (FilterRegistrationBean order -50; Spring Security is at -100) so the authenticated JWT is
 * available. It is a no-op when enforcement is on (the validator already rejects).
 */
public final class AzpReportOnlyFilter extends OncePerRequestFilter {

    private final Set<String> allowed;
    private final boolean enforce;
    private final AzpReportSupport reporter;

    public AzpReportOnlyFilter(String service, Collection<String> allowedAzp, boolean enforce) {
        this.allowed = allowedAzp.stream().filter(s -> s != null && !s.isBlank()).map(String::trim)
                .collect(Collectors.toUnmodifiableSet());
        this.enforce = enforce;
        this.reporter = new AzpReportSupport(service);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!enforce) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth instanceof JwtAuthenticationToken jwtAuth) {
                String azp = jwtAuth.getToken().getClaimAsString("azp");
                if (azp == null || !allowed.contains(azp)) {
                    reporter.reportOnce(azp, request.getMethod(), request.getRequestURI());
                }
            }
        }
        chain.doFilter(request, response);
    }
}
