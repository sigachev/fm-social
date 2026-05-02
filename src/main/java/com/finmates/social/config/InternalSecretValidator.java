package com.finmates.social.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;

/**
 * Fail-fast startup validator for {@code finmates.internal.shared-secret}.
 *
 * <p>Goal: misconfiguration of the inter-service shared secret must produce a loud
 * startup failure rather than silent 401 responses on every {@code /api/internal/**}
 * request.
 *
 * <p>Rules per active profile:
 * <ul>
 *   <li>k8s / prod: secret must be non-blank and must NOT equal the dev fallback
 *       {@code dev-local-secret}. Otherwise throws {@link IllegalStateException}.</li>
 *   <li>dev / test / default: dev fallback is allowed; warn only.</li>
 * </ul>
 *
 * <p>Never logs the secret value — only its length.
 */
@Slf4j
@Component
public class InternalSecretValidator {

    static final String DEV_FALLBACK = "dev-local-secret";
    private static final Set<String> NON_DEV_PROFILES = Set.of("k8s", "prod", "production", "aws");

    @Value("${finmates.internal.shared-secret:}")
    private String sharedSecret;

    private final Environment environment;

    public InternalSecretValidator(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void validate() {
        String[] activeProfiles = environment.getActiveProfiles();
        String profileLabel = activeProfiles.length == 0 ? "default" : String.join(",", activeProfiles);
        boolean isNonDev = Arrays.stream(activeProfiles).anyMatch(NON_DEV_PROFILES::contains);

        boolean blank = sharedSecret == null || sharedSecret.isBlank();
        boolean isDevFallback = DEV_FALLBACK.equals(sharedSecret);

        if (isNonDev && (blank || isDevFallback)) {
            String reason = blank
                    ? "finmates.internal.shared-secret is not set (env var INTERNAL_SHARED_SECRET missing?)"
                    : "finmates.internal.shared-secret equals the known dev fallback '" + DEV_FALLBACK
                            + "' — refusing to start in profile=" + profileLabel;
            log.error("[InternalSecretValidator] STARTUP REFUSED: {}", reason);
            throw new IllegalStateException(reason);
        }

        if (blank) {
            log.warn("[InternalSecretValidator] finmates.internal.shared-secret is blank (profile={}). "
                    + "All /api/internal/** calls will 401.", profileLabel);
            return;
        }

        log.info("[InternalSecretValidator] Internal shared secret configured (length={}, profile={})",
                sharedSecret.length(), profileLabel);
    }
}
