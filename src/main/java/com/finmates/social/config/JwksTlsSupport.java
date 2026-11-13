package com.finmates.social.config;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Enumeration;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * Builds the {@link RestTemplate} the {@code JwtDecoder} uses to fetch the Keycloak JWKS.
 *
 * <p>Outside the {@code dev} profile the TLS certificate and host name are always verified against the
 * default JVM truststore. In-cluster the Keycloak self-signed certificate is pinned by importing it into
 * the image's cacerts (Dockerfile). The optional {@code finmates.security.jwks.trust-store} is ADDITIVE:
 * the effective trust is the default JVM trust anchors PLUS the certificate entries of that store (never
 * only the store); chain + host name verification stay on. This is NOT trust-all. The trust-all variant
 * exists solely for the {@code dev} profile.
 */
public final class JwksTlsSupport {

    private static final Logger log = LoggerFactory.getLogger(JwksTlsSupport.class);

    public static final String DEFAULT_TRUST_STORE_TYPE = "PKCS12";

    private JwksTlsSupport() {
    }

    /**
     * Verified TLS (chain + host name). {@code trustStorePath} blank = default JVM truststore;
     * otherwise the certificates of that file are added to the default JVM trust anchors.
     */
    public static RestTemplate verifiedRestTemplate(String trustStorePath, String trustStorePassword,
                                                    String trustStoreType)
            throws GeneralSecurityException, IOException {
        SSLContext sslContext = customTrustStoreSslContext(trustStorePath, trustStorePassword, trustStoreType);
        if (sslContext == null) {
            // Default HttpsURLConnection behaviour: default truststore + HTTPS host name verification.
            return new RestTemplate(new SimpleClientHttpRequestFactory());
        }

        // Only the socket factory is replaced; the default HostnameVerifier (host name verification) stays.
        return new RestTemplate(new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection conn, String method) throws IOException {
                if (conn instanceof HttpsURLConnection httpsConn) {
                    httpsConn.setSSLSocketFactory(sslContext.getSocketFactory());
                }
                super.prepareConnection(conn, method);
            }
        });
    }

    /**
     * Shared by every TLS client that talks to Keycloak (JWKS fetch, Keycloak admin client): returns
     * {@code null} when {@code trustStorePath} is blank (= use the default JVM truststore), otherwise an
     * {@link SSLContext} that trusts the default JVM trust anchors PLUS the certificate entries of the
     * given truststore (chain still verified; host name verification is left to the HTTP client and stays
     * on). A set-but-unreadable path throws.
     */
    public static SSLContext customTrustStoreSslContext(String trustStorePath, String trustStorePassword,
                                                        String trustStoreType)
            throws GeneralSecurityException, IOException {
        if (trustStorePath == null || trustStorePath.isBlank()) {
            return null;
        }
        String type = (trustStoreType == null || trustStoreType.isBlank())
                ? DEFAULT_TRUST_STORE_TYPE : trustStoreType.trim();
        char[] password = (trustStorePassword == null || trustStorePassword.isEmpty())
                ? null : trustStorePassword.toCharArray();
        Path path = Paths.get(trustStorePath.trim());
        if (!Files.isReadable(path)) {
            throw new IOException("finmates.security.jwks.trust-store is not a readable file: " + path);
        }
        KeyStore trustStore = KeyStore.getInstance(type);
        try (InputStream in = Files.newInputStream(path)) {
            trustStore.load(in, password);
        }
        KeyStore merged = mergedTrustStore(trustStore);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(merged);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, tmf.getTrustManagers(), new SecureRandom());
        log.info("Keycloak TLS client uses additional truststore {} (type {}) on top of the default JVM trust anchors; "
                + "certificate chain and host name are verified", path, type);
        return sslContext;
    }

    /**
     * Default JVM trust anchors plus every certificate entry of {@code custom} (aliases prefixed
     * {@code default-} / {@code custom-}). Key entries of the custom store are ignored.
     */
    static KeyStore mergedTrustStore(KeyStore custom) throws GeneralSecurityException, IOException {
        TrustManagerFactory def = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        def.init((KeyStore) null);
        KeyStore merged = KeyStore.getInstance(KeyStore.getDefaultType());
        merged.load(null, null);
        int i = 0;
        for (TrustManager tm : def.getTrustManagers()) {
            if (tm instanceof X509TrustManager x509) {
                for (X509Certificate c : x509.getAcceptedIssuers()) {
                    merged.setCertificateEntry("default-" + i++, c);
                }
            }
        }
        for (Enumeration<String> a = custom.aliases(); a.hasMoreElements();) {
            String alias = a.nextElement();
            if (custom.isCertificateEntry(alias)) {
                merged.setCertificateEntry("custom-" + alias, custom.getCertificate(alias));
            }
        }
        return merged;
    }

    /**
     * DEV PROFILE ONLY. Accepts any certificate and any host name. Never call outside the
     * {@code dev} profile; a MITM could serve a forged JWKS and mint tokens.
     */
    public static RestTemplate trustAllRestTemplateForDev() throws GeneralSecurityException {
        log.warn("*** INSECURE: JWKS TLS verification is DISABLED (trust-all + no host name check). "
                + "This is only allowed in the 'dev' profile. Never run this against a real environment. ***");
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) { }
                    public void checkServerTrusted(X509Certificate[] certs, String authType) { }
                }
        }, new SecureRandom());
        return new RestTemplate(new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection conn, String method) throws IOException {
                if (conn instanceof HttpsURLConnection httpsConn) {
                    httpsConn.setSSLSocketFactory(sslContext.getSocketFactory());
                    httpsConn.setHostnameVerifier((host, session) -> true);
                }
                super.prepareConnection(conn, method);
            }
        });
    }
}
