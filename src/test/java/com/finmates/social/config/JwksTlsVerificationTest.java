package com.finmates.social.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import javax.net.ssl.SSLException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;

/**
 * Proves the non-dev JWKS fetch verifies TLS: a JWKS served over HTTPS with a self-signed
 * certificate is rejected by the default truststore, accepted only when that exact certificate is
 * configured as an additional (pinned) truststore on top of the default JVM anchors, and still rejected when the
 * host name does not match. A different self-signed certificate stays rejected.
 * The decoder is built through the same static factory the production {@code @Profile("!dev")} bean uses.
 */
class JwksTlsVerificationTest {

    private static final String ISSUER = "https://auth.example.test/realms/finmates";
    private static final String KID = "test-key";
    private static RSAKey rsaKey;
    private static KeyPair keyPair;

    @TempDir
    Path tmp;

    private MockWebServer server;

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        keyPair = g.generateKeyPair();
        rsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic()).keyID(KID).build();
    }

    @AfterEach
    void stop() throws Exception {
        if (server != null) {
            server.shutdown();
        }
    }

    /** HTTPS JWKS server on 127.0.0.1 presenting the given (self-signed) certificate. */
    private String startJwksServer(HeldCertificate cert) throws Exception {
        HandshakeCertificates serverCerts = new HandshakeCertificates.Builder().heldCertificate(cert).build();
        server = new MockWebServer();
        server.useHttps(serverCerts.sslSocketFactory(), false);
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setHeader("Content-Type", "application/json")
                        .setBody(new JWKSet(rsaKey.toPublicJWK()).toString());
            }
        });
        server.start(InetAddress.getByName("127.0.0.1"), 0);
        return "https://127.0.0.1:" + server.getPort() + "/realms/finmates/protocol/openid-connect/certs";
    }

    private static HeldCertificate selfSigned(String san) {
        return new HeldCertificate.Builder().commonName(san).addSubjectAlternativeName(san).build();
    }

    private Path trustStoreWith(HeldCertificate cert, String password) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setCertificateEntry("jwks", cert.certificate());
        Path file = tmp.resolve("jwks-truststore.p12");
        try (OutputStream out = Files.newOutputStream(file)) {
            ks.store(out, password.toCharArray());
        }
        return file;
    }

    private static String token() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(ISSUER).subject("user-1").claim("azp", "finmates-app")
                .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(60))).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KID).build(), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) keyPair.getPrivate()));
        return jwt.serialize();
    }

    private static boolean causedBySslFailure(Throwable t) {
        for (; t != null; t = t.getCause()) {
            if (t instanceof SSLException) {
                return true;
            }
        }
        return false;
    }

    private static NimbusJwtDecoder prodDecoder(String jwksUri, String trustStore, String password, String type)
            throws Exception {
        return SecurityConfig.verifiedJwtDecoder(jwksUri, ISSUER, List.of("finmates-app"), true,
                trustStore, password, type);
    }

    @Test
    void defaultTruststoreRejectsUntrustedSelfSignedJwksServer() throws Exception {
        String uri = startJwksServer(selfSigned("127.0.0.1"));
        NimbusJwtDecoder decoder = prodDecoder(uri, "", "", "PKCS12");

        assertThatThrownBy(() -> decoder.decode(token()))
                .isInstanceOf(JwtException.class)
                .satisfies(e -> assertThat(causedBySslFailure(e)).as("TLS failure in cause chain: %s", e).isTrue());
        assertThat(server.getRequestCount()).as("no HTTP request may be served over an untrusted TLS session").isZero();
    }

    @Test
    void customTruststoreWithTheServerCertificateAcceptsAndDecodes() throws Exception {
        HeldCertificate cert = selfSigned("127.0.0.1");
        String uri = startJwksServer(cert);
        Path trustStore = trustStoreWith(cert, "changeit");

        NimbusJwtDecoder decoder = prodDecoder(uri, trustStore.toString(), "changeit", "PKCS12");

        assertThat(decoder.decode(token()).getSubject()).isEqualTo("user-1");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void customTruststoreStillVerifiesTheHostName() throws Exception {
        HeldCertificate cert = selfSigned("some-other-host.example"); // trusted, but wrong name for 127.0.0.1
        String uri = startJwksServer(cert);
        Path trustStore = trustStoreWith(cert, "changeit");

        NimbusJwtDecoder decoder = prodDecoder(uri, trustStore.toString(), "changeit", "PKCS12");

        assertThatThrownBy(() -> decoder.decode(token()))
                .isInstanceOf(JwtException.class)
                .satisfies(e -> assertThat(causedBySslFailure(e)).as("TLS failure in cause chain: %s", e).isTrue());
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void customTruststoreDoesNotTrustAnUnrelatedCertificate() throws Exception {
        String uri = startJwksServer(selfSigned("127.0.0.1"));
        Path trustStore = trustStoreWith(selfSigned("127.0.0.1"), "changeit"); // a different cert

        NimbusJwtDecoder decoder = prodDecoder(uri, trustStore.toString(), "changeit", "PKCS12");

        assertThatThrownBy(() -> decoder.decode(token())).isInstanceOf(JwtException.class);
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void mergedTrustKeepsTheJvmDefaultAnchorsAndAddsTheCustomCertificate() throws Exception {
        HeldCertificate pinned = selfSigned("127.0.0.1");
        KeyStore custom = KeyStore.getInstance("PKCS12");
        custom.load(null, null);
        custom.setCertificateEntry("keycloak", pinned.certificate());

        KeyStore merged = JwksTlsSupport.mergedTrustStore(custom);

        javax.net.ssl.TrustManagerFactory def =
                javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        def.init((KeyStore) null);
        java.util.Set<java.security.cert.X509Certificate> defaults = new java.util.HashSet<>();
        for (javax.net.ssl.TrustManager tm : def.getTrustManagers()) {
            if (tm instanceof javax.net.ssl.X509TrustManager x509) {
                defaults.addAll(java.util.Arrays.asList(x509.getAcceptedIssuers()));
            }
        }
        assertThat(defaults).as("JVM default trust anchors").isNotEmpty();
        assertThat(merged.size()).as("not a single-cert store").isGreaterThan(defaults.size() - 1);
        for (java.security.cert.X509Certificate anchor : defaults) {
            assertThat(merged.getCertificateAlias(anchor)).as("default anchor kept: %s", anchor.getSubjectX500Principal())
                    .isNotNull();
        }
        assertThat(merged.getCertificateAlias(pinned.certificate())).as("pinned cert added").isNotNull();
    }

    @Test
    void unreadableTruststorePathFailsFast() {
        String missing = tmp.resolve("missing.p12").toString();
        assertThatThrownBy(() -> prodDecoder("https://127.0.0.1:1/certs", missing, "x", "PKCS12"))
                .isInstanceOf(IOException.class);
    }

    @Test
    void devTrustAllDecoderAcceptsSelfSignedServer() throws Exception {
        String uri = startJwksServer(selfSigned("some-other-host.example"));
        NimbusJwtDecoder decoder = SecurityConfig.trustAllJwtDecoder(uri, ISSUER, List.of("finmates-app"), true);

        assertThat(decoder.decode(token()).getSubject()).isEqualTo("user-1");
    }
}
