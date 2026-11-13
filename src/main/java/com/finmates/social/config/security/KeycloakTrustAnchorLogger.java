package com.finmates.social.config.security;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Startup log line for the pinned in-cluster Keycloak certificate (public material, no secrets).
 *
 * <p>Pods reach Keycloak's own TLS port, which serves a self-signed cert that the image imports into the
 * JVM cacerts (Dockerfile keytool import of cert/self-signed/finmates.cert, kept in the image at
 * /opt/finmates/finmates.cert). A rotation of that cert would break every JWKS fetch / Keycloak admin
 * call (PKIX), so this logs subject, expiry, SHA-256 and whether the JVM trusts it, WARNs/ERRORs on
 * mismatch, untrusted or near expiry. It never throws. Canonical copy: F:\Projects\docs\adr\keycloak-tls-pin.
 */
@Component
public class KeycloakTrustAnchorLogger {

    private static final Logger log = LoggerFactory.getLogger(KeycloakTrustAnchorLogger.class);
    static final Duration EXPIRY_WARNING = Duration.ofDays(60);

    private final String certPath;
    private final String expectedSha256;

    public KeycloakTrustAnchorLogger(
            @Value("${finmates.security.keycloak.pinned-cert-path:/opt/finmates/finmates.cert}") String certPath,
            @Value("${finmates.security.keycloak.pinned-cert-sha256:"
                    + "C2:38:AA:4F:9B:D7:92:AA:1A:85:EF:B2:17:69:1C:2E:82:30:76:53:6D:24:58:76:A7:05:18:4E:C0:99:D4:A0}")
            String expectedSha256) {
        this.certPath = certPath;
        this.expectedSha256 = expectedSha256;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        inspect(Path.of(certPath), expectedSha256, Clock.systemUTC(), defaultTrustManager());
    }

    /** Logs and returns the outcome; never throws. */
    static Outcome inspect(Path file, String expectedSha256, Clock clock, X509TrustManager jvmTrust) {
        try {
            if (!Files.isReadable(file)) {
                log.info("Keycloak pinned certificate file {} is not present (local run?); relying on the JVM "
                        + "truststore as it is", file);
                return Outcome.ABSENT;
            }
            X509Certificate cert;
            try (InputStream in = Files.newInputStream(file)) {
                cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
            }
            String sha256 = sha256(cert);
            boolean trusted = isTrusted(cert, jvmTrust);
            Instant notAfter = cert.getNotAfter().toInstant();
            log.info("Keycloak pinned certificate: subject='{}' notAfter={} sha256={} trustedByJvm={}",
                    cert.getSubjectX500Principal().getName(), notAfter, sha256, trusted);
            Outcome outcome = Outcome.OK;
            if (!trusted) {
                log.warn("Keycloak pinned certificate is NOT trusted by the JVM truststore: JWKS fetches and Keycloak "
                        + "admin calls over the in-cluster path will fail with PKIX errors. The image must import "
                        + "cert/self-signed/finmates.cert into cacerts (Dockerfile keytool -importcert).");
                outcome = Outcome.UNTRUSTED;
            }
            if (expectedSha256 != null && !expectedSha256.isBlank() && !expectedSha256.trim().equalsIgnoreCase(sha256)) {
                log.error("Keycloak pinned certificate fingerprint MISMATCH: expected {} but {} has {}. The Keycloak "
                        + "certificate or the pinned file changed; JWKS may break after a Keycloak rotation.",
                        expectedSha256.trim(), file, sha256);
                outcome = Outcome.MISMATCH;
            }
            Instant now = clock.instant();
            if (notAfter.isBefore(now)) {
                log.error("Keycloak pinned certificate EXPIRED at {}", notAfter);
                outcome = Outcome.EXPIRING;
            } else if (notAfter.isBefore(now.plus(EXPIRY_WARNING))) {
                log.warn("Keycloak pinned certificate expires within {} days (notAfter={})",
                        EXPIRY_WARNING.toDays(), notAfter);
                if (outcome == Outcome.OK) {
                    outcome = Outcome.EXPIRING;
                }
            }
            return outcome;
        } catch (Exception e) {
            log.warn("Could not inspect the Keycloak pinned certificate {}: {}", file, e.toString());
            return Outcome.ERROR;
        }
    }

    private static boolean isTrusted(X509Certificate cert, X509TrustManager jvmTrust) {
        if (jvmTrust == null) {
            return false;
        }
        try {
            jvmTrust.checkServerTrusted(new X509Certificate[] {cert}, cert.getPublicKey().getAlgorithm());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static X509TrustManager defaultTrustManager() {
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager x509) {
                    return x509;
                }
            }
        } catch (Exception e) {
            log.warn("Could not load the default JVM trust manager: {}", e.toString());
        }
        return null;
    }

    static String sha256(X509Certificate cert) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
        String hex = HexFormat.ofDelimiter("").withUpperCase().formatHex(digest);
        return IntStream.range(0, hex.length() / 2).mapToObj(i -> hex.substring(2 * i, 2 * i + 2))
                .collect(Collectors.joining(":"));
    }

    enum Outcome { OK, ABSENT, UNTRUSTED, MISMATCH, EXPIRING, ERROR }
}
