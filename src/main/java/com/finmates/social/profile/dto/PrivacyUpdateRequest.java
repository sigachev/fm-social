package com.finmates.social.profile.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code PATCH /api/profiles/me/privacy}.
 *
 * <p>{@code isPrivate} maps to {@code Profile.isPrivate}. The flip from {@code true → false}
 * triggers an auto-accept of all PENDING incoming follow requests (with feed backfill) in a
 * single transaction. The flip from {@code false → true} does NOT retroactively touch
 * existing ACTIVE follows — only new follows on a private profile go to PENDING.</p>
 */
public record PrivacyUpdateRequest(
        @NotNull Boolean isPrivate
) {}
