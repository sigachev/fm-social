package com.finmates.social.config.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * Keeps tokens issued to the MCP server ({@code azp=fm-mcp}, obtained by Keycloak token exchange) on a short list of
 * endpoints. Registered right after the bearer-token filter, so the JWT is already validated (signature, issuer,
 * expiry, azp allowlist). For a JWT whose {@code azp} is an MCP client every one of these must hold, otherwise the
 * request is refused (401 for the secret, 403 for the rest, generic body, reason only logged):
 * <ul>
 *   <li>{@code X-Internal-Secret} matches {@code finmates.internal.shared-secret} (the token alone is not enough:
 *       only fm-mcp inside the cluster can call);</li>
 *   <li>{@code aud} contains this service's own client id (a token exchanged for another service is refused);</li>
 *   <li>a positive integer {@code user_id} claim (identity comes from the token, never from a parameter);</li>
 *   <li>the method and decoded path match one of the allowed routes ({@code "GET /api/feed/**"}). Paths with
 *       {@code ..}, {@code ;}, {@code %}, backslashes or empty segments never match.</li>
 * </ul>
 * Tokens of other clients and anonymous requests pass through untouched.
 */
public final class McpClientGuardFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Internal-Secret";
    private static final Logger log = LoggerFactory.getLogger(McpClientGuardFilter.class);
    private static final AntPathMatcher MATCHER = new AntPathMatcher();
    private static final UrlPathHelper PATHS = new UrlPathHelper();

    private final Set<String> mcpAzp;
    private final String audience;
    private final byte[] secret;
    private final List<String[]> routes;

    /** azp of the MCP server's exchanged tokens; used where no servlet filter runs (e.g. WebSocket CONNECT). */
    public static final String MCP_AZP = "fm-mcp";

    /** Default MCP client ids from a comma-separated property value; blank means {@link #MCP_AZP}. */
    public static List<String> azpList(String csv) {
        return csv == null || csv.isBlank() ? List.of(MCP_AZP)
                : java.util.Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public McpClientGuardFilter(Collection<String> mcpAzp, String audience, String sharedSecret,
            Collection<String> routes) {
        this.mcpAzp = clean(mcpAzp);
        this.audience = audience;
        this.secret = sharedSecret == null || sharedSecret.isBlank() ? null
                : sharedSecret.getBytes(StandardCharsets.UTF_8);
        this.routes = clean(routes).stream().map(r -> r.split("\\s+", 2)).filter(r -> r.length == 2)
                .map(r -> new String[] {r[0].toUpperCase(Locale.ROOT), r[1]}).toList();
    }

    private static Set<String> clean(Collection<String> values) {
        return values == null ? Set.of() : values.stream().filter(s -> s != null && !s.isBlank()).map(String::trim)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwt)) {
            chain.doFilter(request, response);
            return;
        }
        String azp = jwt.getToken().getClaimAsString("azp");
        if (azp == null || !mcpAzp.contains(azp)) {
            chain.doFilter(request, response);
            return;
        }
        String provided = request.getHeader(HEADER);
        if (secret == null || provided == null
                || !MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), secret)) {
            deny(response, HttpServletResponse.SC_UNAUTHORIZED, "internal secret missing or wrong", request);
            return;
        }
        List<String> aud = jwt.getToken().getAudience();
        if (audience == null || aud == null || !aud.contains(audience)) {
            deny(response, HttpServletResponse.SC_FORBIDDEN, "token not issued for " + audience, request);
            return;
        }
        if (!positiveIntegral(jwt.getToken().getClaim("user_id"))) {
            deny(response, HttpServletResponse.SC_FORBIDDEN, "missing or invalid user_id claim", request);
            return;
        }
        String raw = request.getRequestURI();
        if (raw == null || raw.contains(";") || raw.contains("%") || raw.contains("..") || raw.contains("\\")
                || !allowed(request.getMethod(), PATHS.getPathWithinApplication(request))) {
            deny(response, HttpServletResponse.SC_FORBIDDEN, "route not allowed for MCP", request);
            return;
        }
        chain.doFilter(request, response);
    }

    boolean allowed(String method, String path) {
        if (method == null || path == null || path.isEmpty() || path.contains("..") || path.contains(";")
                || path.contains("%") || path.contains("\\") || path.contains("//")) {
            return false;
        }
        String m = method.toUpperCase(Locale.ROOT);
        for (String[] route : routes) {
            if (route[0].equals(m) && MATCHER.match(route[1], path)) {
                return true;
            }
        }
        return false;
    }

    private static boolean positiveIntegral(Object value) {
        try {
            if (value instanceof Number n) {
                return n.doubleValue() == Math.rint(n.doubleValue()) && n.longValue() > 0;
            }
            return value instanceof String s && s.matches("[0-9]{1,18}") && Long.parseLong(s) > 0;
        }
        catch (RuntimeException e) {
            return false;
        }
    }

    private static void deny(HttpServletResponse response, int status, String reason, HttpServletRequest request)
            throws IOException {
        log.warn("event=mcp_client_denied reason=\"{}\" method={}", reason, request.getMethod());
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"forbidden\"}");
    }
}
