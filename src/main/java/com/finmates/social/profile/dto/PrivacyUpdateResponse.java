package com.finmates.social.profile.dto;

/**
 * Response from {@code PATCH /api/profiles/me/privacy}.
 *
 * <p>{@code autoAcceptedCount} is the number of PENDING incoming follow requests that were
 * promoted to ACTIVE because the privacy flip was {@code true → false}. Always 0 for the
 * {@code false → true} flip and for no-op flips.</p>
 */
public record PrivacyUpdateResponse(
        boolean isPrivate,
        int autoAcceptedCount
) {}
