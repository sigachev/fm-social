package com.finmates.social.common.security;

import com.finmates.social.common.exception.ForbiddenActionException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class AuthenticatedUser {

    /**
     * Returns the username from the JWT preferred_username claim.
     * Configured via JwtAuthConverter to be the principal name.
     */
    public String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new ForbiddenActionException("Not authenticated");
        }
        return auth.getName();
    }

    /**
     * Returns the user_id (BIGINT) extracted from the JWT 'user_id' custom claim.
     *
     * <p>IMPORTANT: This requires finmates-main to mint a 'user_id' claim in all tokens.
     * This is done via the /auth/me find-or-create flow. If the JWT does not contain this
     * claim, this method throws ForbiddenActionException — the caller will receive 403.
     *
     * <p>TODO Prompt 5 — if user_id claim is not present, fall back to cross-service call
     * to finmates-main /api/internal/users/by-username with Caffeine caching.
     */
    public Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth)) {
            throw new ForbiddenActionException("Not authenticated");
        }
        Object userIdClaim = jwtAuth.getToken().getClaim("user_id");
        if (userIdClaim == null) {
            throw new ForbiddenActionException(
                    "JWT missing user_id claim — finmates-main must populate this via /auth/me");
        }
        return Long.valueOf(userIdClaim.toString());
    }

    /**
     * Returns the authenticated viewer's user_id, or {@code null} if the request
     * is anonymous (no JWT) or the JWT is missing the {@code user_id} claim.
     *
     * <p>Canonical pattern for <strong>auth-optional</strong> endpoints in fm-social.
     * Prefer this helper over reaching for {@code @AuthenticationPrincipal(required = false)}
     * or reading {@code SecurityContextHolder} inline — keeps the JWT extraction
     * logic centralized and the behavior consistent with {@link #currentUserId()}.
     *
     * <p>Use case: a controller method whose URL is configured {@code permitAll()}
     * in SecurityConfig, but which behaves differently based on whether a viewer
     * is identified (e.g. {@code GET /api/posts/by-cashtag} defaults to network
     * scope when authenticated and global scope when anonymous).
     */
    public Long currentUserIdOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth)) {
            return null;
        }
        Object userIdClaim = jwtAuth.getToken().getClaim("user_id");
        if (userIdClaim == null) {
            return null;
        }
        return Long.valueOf(userIdClaim.toString());
    }
}
