package com.finmates.social.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class McpClientGuardFilterTest {

    private static final String SECRET = "test-internal-secret";
    private final McpClientGuardFilter filter = new McpClientGuardFilter(List.of("fm-mcp"), "finmates-services", SECRET,
            List.of("GET /api/feed/**", "POST /api/x/*/do"));

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void auth(String azp, String aud, Object userId) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256").subject("s").claim("azp", azp)
                .audience(List.of(aud)).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (userId != null) {
            b.claim("user_id", userId);
        }
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(b.build()));
    }

    private static int run(McpClientGuardFilter f, String method, String path, String secret, AtomicBoolean passed)
            throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        if (secret != null) {
            req.addHeader("X-Internal-Secret", secret);
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req, res, (rq, rs) -> passed.set(true));
        return res.getStatus();
    }

    private int run(String method, String path, String secret) throws Exception {
        AtomicBoolean passed = new AtomicBoolean();
        int status = run(filter, method, path, secret, passed);
        return passed.get() ? 0 : status;
    }

    @Test
    void mcpTokenOnAnAllowedRoutePasses() throws Exception {
        auth("fm-mcp", "finmates-services", 1001L);
        assertThat(run("GET", "/api/feed/following", SECRET)).isZero();
        assertThat(run("POST", "/api/x/5/do", SECRET)).isZero();
    }

    @Test
    void mcpTokenIsRefusedWithoutTheRightSecretOrAudienceOrUser() throws Exception {
        auth("fm-mcp", "finmates-services", 1001L);
        assertThat(run("GET", "/api/feed/following", null)).isEqualTo(401);
        assertThat(run("GET", "/api/feed/following", "wrong")).isEqualTo(401);
        auth("fm-mcp", "finmates-crypto", 1001L);
        assertThat(run("GET", "/api/feed/following", SECRET)).isEqualTo(403);
        auth("fm-mcp", "finmates-services", null);
        assertThat(run("GET", "/api/feed/following", SECRET)).isEqualTo(403);
        auth("fm-mcp", "finmates-services", "0");
        assertThat(run("GET", "/api/feed/following", SECRET)).isEqualTo(403);
    }

    @Test
    void mcpTokenIsRefusedOffTheList() throws Exception {
        auth("fm-mcp", "finmates-services", 1001L);
        assertThat(run("DELETE", "/api/feed/following", SECRET)).isEqualTo(403);
        assertThat(run("GET", "/api/other", SECRET)).isEqualTo(403);
        assertThat(run("POST", "/api/x/5/do/../../admin", SECRET)).isEqualTo(403);
        assertThat(run("GET", "/api/feed/following;x=1", SECRET)).isEqualTo(403);
    }

    @Test
    void otherClientsAndAnonymousArePassedThrough() throws Exception {
        auth("finmates-app", "account", 7L);
        assertThat(run("DELETE", "/api/anything", null)).isZero();
        SecurityContextHolder.clearContext();
        assertThat(run("GET", "/api/anything", null)).isZero();
    }

    @Test
    void noSecretConfiguredFailsClosed() throws Exception {
        McpClientGuardFilter unset = new McpClientGuardFilter(List.of("fm-mcp"), "finmates-services", "",
                List.of("GET /api/feed/**"));
        auth("fm-mcp", "finmates-services", 1001L);
        AtomicBoolean passed = new AtomicBoolean();
        assertThat(run(unset, "GET", "/api/feed/following", "", passed)).isEqualTo(401);
        assertThat(passed).isFalse();
        assertThat(Map.of()).isEmpty();
    }
}
