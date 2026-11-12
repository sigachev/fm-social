package com.finmates.social.config.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>When {@code enforce} is false the validator only logs (once per distinct azp) and lets the token
 * through, so the guard can be rolled out in report-only mode first.
 */
public final class AzpAllowlistValidator implements OAuth2TokenValidator<Jwt> {

    private static final Logger log = LoggerFactory.getLogger(AzpAllowlistValidator.class);
    private static final OAuth2Error INVALID_AZP = new OAuth2Error(
            "invalid_token", "The token's authorized party (azp) is not allowed for this API.", null);
    private static final int MAX_REPORTED = 100;
    private static final int MAX_LOGGED_LENGTH = 64;

    private final Set<String> allowed;
    private final boolean enforce;
    private final Set<String> reported = new LinkedHashSet<>();

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
        if (azp != null && allowed.contains(azp)) {
            return OAuth2TokenValidatorResult.success();
        }
        if (!enforce) {
            reportOnce(azp);
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(INVALID_AZP);
    }

    private void reportOnce(String azp) {
        String safe = sanitize(azp);
        boolean first;
        synchronized (reported) {
            first = reported.size() < MAX_REPORTED && reported.add(safe);
        }
        if (first) {
            log.warn("azp guard (report-only): token azp='{}' is not in the allowlist and would be rejected "
                    + "once enforcement is on", safe);
        }
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "<absent>";
        }
        String cleaned = value.replaceAll("[^\\x20-\\x7E]", "?");
        return cleaned.length() > MAX_LOGGED_LENGTH ? cleaned.substring(0, MAX_LOGGED_LENGTH) + "..." : cleaned;
    }
}
