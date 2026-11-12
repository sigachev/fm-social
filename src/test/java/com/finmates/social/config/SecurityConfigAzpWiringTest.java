package com.finmates.social.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Proves the decoder built by {@code SecurityConfig.withTokenValidators} (the same method the real
 * {@code jwtDecoder()} bean uses) enforces the azp allowlist on top of the issuer/timestamp checks,
 * and that a rejection surfaces as the resource server's normal invalid_token (401) path.
 */
class SecurityConfigAzpWiringTest {

    private static final String ISSUER = "https://auth.example.test/realms/finmates";
    private static KeyPair keyPair;

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        keyPair = g.generateKeyPair();
    }

    private static NimbusJwtDecoder decoder(boolean enforce) {
        NimbusJwtDecoder base = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keyPair.getPublic()).build();
        return SecurityConfig.withTokenValidators(base, ISSUER, List.of("finmates-app"), enforce);
    }

    private static String token(String issuer, String azp, long expiresInSeconds) throws Exception {
        JWTClaimsSet.Builder c = new JWTClaimsSet.Builder().issuer(issuer).subject("user-1")
                .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(expiresInSeconds)));
        if (azp != null) {
            c.claim("azp", azp);
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), c.build());
        jwt.sign(new RSASSASigner((RSAPrivateKey) keyPair.getPrivate()));
        return jwt.serialize();
    }

    @Test
    void allowedAzpIsAccepted() throws Exception {
        assertThat(decoder(true).decode(token(ISSUER, "finmates-app", 60)).getSubject()).isEqualTo("user-1");
    }

    @Test
    void disallowedAzpIsRejected() throws Exception {
        assertThatThrownBy(() -> decoder(true).decode(token(ISSUER, "dcr-client-123", 60)))
                .isInstanceOf(BadJwtException.class).hasMessageContaining("azp");
    }

    @Test
    void missingAzpIsRejected() throws Exception {
        assertThatThrownBy(() -> decoder(true).decode(token(ISSUER, null, 60))).isInstanceOf(BadJwtException.class);
    }

    @Test
    void reportOnlyModeAcceptsDisallowedAzp() throws Exception {
        assertThat(decoder(false).decode(token(ISSUER, "dcr-client-123", 60))).isNotNull();
    }

    @Test
    void existingIssuerAndExpiryValidatorsAreStillApplied() throws Exception {
        assertThatThrownBy(() -> decoder(true).decode(token("https://evil.example/realms/x", "finmates-app", 60)))
                .isInstanceOf(BadJwtException.class);
        assertThatThrownBy(() -> decoder(true).decode(token(ISSUER, "finmates-app", -3600)))
                .isInstanceOf(BadJwtException.class);
    }

    @Test
    void rejectedTokenSurfacesAsInvalidBearerTokenNotServerError() throws Exception {
        // BearerTokenAuthenticationFilter maps InvalidBearerTokenException to 401 invalid_token.
        JwtAuthenticationProvider provider = new JwtAuthenticationProvider(decoder(true));
        assertThatThrownBy(() -> provider.authenticate(
                new BearerTokenAuthenticationToken(token(ISSUER, "dcr-client-123", 60))))
                .isInstanceOf(InvalidBearerTokenException.class);
        assertThat(provider.authenticate(
                new BearerTokenAuthenticationToken(token(ISSUER, "finmates-app", 60)))).isNotNull();
    }
}
