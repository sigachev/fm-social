package com.finmates.social.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class AzpReportOnlyFilterTest {

    private static final String SECRET_TOKEN_VALUE = "super-secret-token-value";
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger logger;

    @BeforeEach
    void attach() {
        logger = (Logger) LoggerFactory.getLogger(AzpReportSupport.class);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
        SecurityContextHolder.clearContext();
    }

    private static void authenticateWithAzp(String azp) {
        Jwt.Builder b = Jwt.withTokenValue(SECRET_TOKEN_VALUE).header("alg", "none").subject("u")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (azp != null) {
            b.claim("azp", azp);
        }
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(b.build()));
    }

    private MockFilterChain run(AzpReportOnlyFilter filter, String uri) throws Exception {
        var request = new MockHttpServletRequest("GET", uri);
        request.setQueryString("access_token=" + SECRET_TOKEN_VALUE);
        var chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return chain;
    }

    @Test
    void reportOnlyLogsServiceEndpointAzpButNeverTokenOrQuery() throws Exception {
        authenticateWithAzp("dcr-client");
        var chain = run(new AzpReportOnlyFilter("fm-example", List.of("finmates-app"), false), "/api/portfolios/7/positions");
        assertThat(chain.getRequest()).isNotNull();
        assertThat(appender.list).hasSize(1);
        String msg = appender.list.get(0).getFormattedMessage();
        assertThat(msg).contains("service=fm-example", "GET /api/portfolios/{id}/...", "azp='dcr-client'")
                .doesNotContain(SECRET_TOKEN_VALUE).doesNotContain("access_token");
    }

    @Test
    void allowedAzpIsNotReported() throws Exception {
        authenticateWithAzp("finmates-app");
        run(new AzpReportOnlyFilter("svc", List.of("finmates-app"), false), "/api/x");
        assertThat(appender.list).isEmpty();
    }

    @Test
    void enforcingModeDoesNotLogAndNoAuthDoesNotLog() throws Exception {
        authenticateWithAzp("dcr-client");
        run(new AzpReportOnlyFilter("svc", List.of("finmates-app"), true), "/api/x");
        SecurityContextHolder.clearContext();
        run(new AzpReportOnlyFilter("svc", List.of("finmates-app"), false), "/api/x");
        assertThat(appender.list).isEmpty();
    }

    @Test
    void missingAzpIsReportedAsAbsent() throws Exception {
        authenticateWithAzp(null);
        run(new AzpReportOnlyFilter("svc", List.of("finmates-app"), false), "/api/x");
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("azp='<absent>'");
    }
}
