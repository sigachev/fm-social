package com.finmates.social.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;

import javax.net.ssl.X509TrustManager;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** The committed cert file must be the one pods see: SHA-256 C2:38:AA:4F:...:D4:A0 (checked 2026-11-12). */
class KeycloakTrustAnchorLoggerTest {

    static final String PINNED_SHA256 =
            "C2:38:AA:4F:9B:D7:92:AA:1A:85:EF:B2:17:69:1C:2E:82:30:76:53:6D:24:58:76:A7:05:18:4E:C0:99:D4:A0";
    static final Path CERT = Path.of("cert", "self-signed", "finmates.cert");
    static final Clock NOW = Clock.fixed(Instant.parse("2026-11-12T00:00:00Z"), ZoneOffset.UTC);

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger logger;

    @BeforeEach
    void attach() {
        logger = (Logger) LoggerFactory.getLogger(KeycloakTrustAnchorLogger.class);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    private static X509TrustManager trusting(boolean trusted) {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) { }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (!trusted) {
                    throw new CertificateException("not a trust anchor");
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList());
    }

    @Test
    void committedCertIsTheInClusterKeycloakCert() {
        var outcome = KeycloakTrustAnchorLogger.inspect(CERT, PINNED_SHA256, NOW, trusting(true));
        assertThat(outcome).isEqualTo(KeycloakTrustAnchorLogger.Outcome.OK);
        assertThat(messages()).anyMatch(m -> m.contains("sha256=" + PINNED_SHA256)
                && m.contains("CN=auth.finmates.com,CN=keycloak") && m.contains("trustedByJvm=true"));
        assertThat(appender.list).noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.WARN));
    }

    @Test
    void untrustedByJvmIsWarned() {
        var outcome = KeycloakTrustAnchorLogger.inspect(CERT, PINNED_SHA256, NOW, trusting(false));
        assertThat(outcome).isEqualTo(KeycloakTrustAnchorLogger.Outcome.UNTRUSTED);
        assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("NOT trusted"));
    }

    @Test
    void fingerprintMismatchIsAnError() {
        var other = PINNED_SHA256.replace("C2:38", "AA:BB");
        var outcome = KeycloakTrustAnchorLogger.inspect(CERT, other, NOW, trusting(true));
        assertThat(outcome).isEqualTo(KeycloakTrustAnchorLogger.Outcome.MISMATCH);
        assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.ERROR
                && e.getFormattedMessage().contains("MISMATCH"));
    }

    @Test
    void nearExpiryAndExpiredAreFlagged() {
        var near = Clock.fixed(Instant.parse("2034-08-01T00:00:00Z"), ZoneOffset.UTC);
        assertThat(KeycloakTrustAnchorLogger.inspect(CERT, PINNED_SHA256, near, trusting(true)))
                .isEqualTo(KeycloakTrustAnchorLogger.Outcome.EXPIRING);
        var expired = Clock.fixed(Instant.parse("2035-01-01T00:00:00Z"), ZoneOffset.UTC);
        KeycloakTrustAnchorLogger.inspect(CERT, PINNED_SHA256, expired, trusting(true));
        assertThat(appender.list).anyMatch(e -> e.getFormattedMessage().contains("EXPIRED"));
    }

    @Test
    void missingOrGarbageFileNeverThrows(@TempDir Path dir) throws Exception {
        assertThat(KeycloakTrustAnchorLogger.inspect(dir.resolve("absent.cert"), PINNED_SHA256, NOW, trusting(true)))
                .isEqualTo(KeycloakTrustAnchorLogger.Outcome.ABSENT);
        Path garbage = dir.resolve("garbage.cert");
        Files.writeString(garbage, "not a certificate");
        assertThat(KeycloakTrustAnchorLogger.inspect(garbage, PINNED_SHA256, NOW, trusting(true)))
                .isEqualTo(KeycloakTrustAnchorLogger.Outcome.ERROR);
    }

    @Test
    void logLineContainsNoKeyMaterial() {
        KeycloakTrustAnchorLogger.inspect(CERT, PINNED_SHA256, NOW, trusting(true));
        assertThat(messages()).noneMatch(m -> m.contains("PRIVATE") || m.contains("BEGIN "));
    }
}
