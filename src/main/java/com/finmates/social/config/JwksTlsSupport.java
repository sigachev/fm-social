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
 * <p>Outside the {@code dev} profile the TLS certificate and host name are always verified:
 * either against the default JVM truststore, or - when {@code finmates.security.jwks.trust-store}
 * is set - against that truststore only (still with full chain + host name verification; this is
 * NOT trust-all). The trust-all variant exists solely for the {@code dev} profile.
 */
public final class JwksTlsSupport {

    private static final Logger log = LoggerFactory.getLogger(JwksTlsSupport.class);

    public static final String DEFAULT_TRUST_STORE_TYPE = "PKCS12";

    private JwksTlsSupport() {
    }

    /**
     * Verified TLS (chain + host name). {@code trustStorePath} blank = default JVM truststore;
     * otherwise a truststore loaded from that file replaces the default one for this client.
     */
    public static RestTemplate verifiedRestTemplate(String trustStorePath, String trustStorePassword,
                                                    String trustStoreType)
            throws GeneralSecurityException, IOException {
        if (trustStorePath == null || trustStorePath.isBlank()) {
            // Default HttpsURLConnection behaviour: default truststore + HTTPS host name verification.
            return new RestTemplate(new SimpleClientHttpRequestFactory());
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
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, tmf.getTrustManagers(), new SecureRandom());
        log.info("JWKS fetch uses custom truststore {} (type {}); certificate chain and host name are verified",
                path, type);

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
