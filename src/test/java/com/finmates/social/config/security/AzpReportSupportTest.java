package com.finmates.social.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class AzpReportSupportTest {

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
    }

    @Test
    void logsServiceEndpointAndAzpOncePerDistinctCombination() {
        var s = new AzpReportSupport("fm-example");
        s.reportOnce("dcr-client", "GET", "/api/portfolios/42/positions");
        s.reportOnce("dcr-client", "GET", "/api/portfolios/43/positions");
        s.reportOnce("dcr-client", "POST", "/api/portfolios/42/trades");
        assertThat(appender.list).hasSize(2);
        String first = appender.list.get(0).getFormattedMessage();
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(first).contains("service=fm-example", "GET /api/portfolios/{id}/...", "azp='dcr-client'");
    }

    @Test
    void normalizePathMasksIdsAddressesUuidsAndTruncates() {
        assertThat(AzpReportSupport.normalizePath("/api/wallets/0x" + "a".repeat(40) + "/holdings"))
                .isEqualTo("/api/wallets/{id}/...");
        assertThat(AzpReportSupport.normalizePath("/api/x/123e4567-e89b-12d3-a456-426614174000"))
                .isEqualTo("/api/x/{id}");
        assertThat(AzpReportSupport.normalizePath("/a/b/c/d/e")).isEqualTo("/a/b/c/...");
        assertThat(AzpReportSupport.normalizePath("/")).isEqualTo("/");
        assertThat(AzpReportSupport.normalizePath(null)).isEqualTo("/");
    }

    @Test
    void absentAzpAndControlCharactersAreSanitized() {
        var s = new AzpReportSupport("svc");
        s.reportOnce(null, "GET", "/x");
        s.reportOnce("evil\nINJECTED", "GET", "/x");
        assertThat(appender.list).hasSize(2);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("azp='<absent>'");
        assertThat(appender.list.get(1).getFormattedMessage()).doesNotContain("\n").contains("evil?INJECTED");
    }

    @Test
    void reportedSetIsBounded() {
        var s = new AzpReportSupport("svc");
        for (int i = 0; i < 600; i++) {
            s.reportOnce("azp-" + i, "GET", "/x");
        }
        assertThat(appender.list).hasSize(500);
    }
}
