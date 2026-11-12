package com.finmates.social.config.security;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Accepts a Keycloak access token only if its {@code azp} (authorized party) claim is on an allowlist.
 *
 * <p>Why: every Finmates resource server trusts any token signed by the {@code finmates} realm. Tokens
 * minted for third-party / dynamically registered clients (e.g. MCP connectors) would otherwise be
 * replayable against the full API. Canonical copy lives in {@code F:\Projects\docs\adr\azp-guard};
 * the per-service copies must stay byte-identical apart from the package line.
 *
 * <p>When {@code enforce} is false the validator lets every token through. Report-only logging
 * (service, endpoint, azp) is done by {@code AzpReportOnlyFilter} / {@code AzpReportOnlyWebFilter},
 * which can see the request; a validator cannot.
 */
public final class AzpAllowlistValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error INVALID_AZP = new OAuth2Error(
            "invalid_token", "The token's authorized party (azp) is not allowed for this API.", null);

    private final Set<String> allowed;
    private final boolean enforce;

    public AzpAllowlistValidator(Collection<String> allowedAzp, boolean enforce) {
        this.allowed = allowedAzp == null ? Set.of() : allowedAzp.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .collect(Collectors.toUnmodifiableSet());
        if (this.allowed.isEmpty()) {
            throw new IllegalArgumentException("azp allowlist must not be empty");
        }
        this.enforce = enforce;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        String azp = jwt.getClaimAsString("azp");
        if (!enforce || (azp != null && allowed.contains(azp))) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(INVALID_AZP);
    }
}
