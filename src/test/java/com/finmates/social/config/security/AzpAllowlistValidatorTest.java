package com.finmates.social.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class AzpAllowlistValidatorTest {

    private static Jwt jwtWithAzp(String azp) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "none").subject("user-1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (azp != null) {
            b.claim("azp", azp);
        }
        return b.build();
    }

    @Test
    void allowedAzpPassesWhenEnforcing() {
        var v = new AzpAllowlistValidator(List.of("finmates-app", "fm-mcp"), true);
        assertThat(v.validate(jwtWithAzp("finmates-app")).hasErrors()).isFalse();
        assertThat(v.validate(jwtWithAzp("fm-mcp")).hasErrors()).isFalse();
    }

    @Test
    void unknownAzpRejectedWhenEnforcing() {
        var v = new AzpAllowlistValidator(List.of("finmates-app"), true);
        var result = v.validate(jwtWithAzp("3f2a-dcr-client"));
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).extracting("errorCode").containsExactly("invalid_token");
    }

    @Test
    void missingAzpRejectedWhenEnforcing() {
        var v = new AzpAllowlistValidator(List.of("finmates-app"), true);
        assertThat(v.validate(jwtWithAzp(null)).hasErrors()).isTrue();
    }

    @Test
    void reportOnlyModeNeverRejects() {
        var v = new AzpAllowlistValidator(List.of("finmates-app"), false);
        assertThat(v.validate(jwtWithAzp("someone-else")).hasErrors()).isFalse();
        assertThat(v.validate(jwtWithAzp(null)).hasErrors()).isFalse();
    }

    @Test
    void allowlistEntriesAreTrimmedAndBlanksIgnored() {
        var v = new AzpAllowlistValidator(List.of(" finmates-app ", " ", ""), true);
        assertThat(v.validate(jwtWithAzp("finmates-app")).hasErrors()).isFalse();
    }

    @Test
    void emptyAllowlistIsRejectedAtConstruction() {
        assertThatThrownBy(() -> new AzpAllowlistValidator(List.of(" "), true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AzpAllowlistValidator(null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void azpMatchingIsExactAndCaseSensitive() {
        var v = new AzpAllowlistValidator(List.of("finmates-app"), true);
        assertThat(v.validate(jwtWithAzp("Finmates-App")).hasErrors()).isTrue();
        assertThat(v.validate(jwtWithAzp("finmates-app ")).hasErrors()).isTrue();
    }
}
