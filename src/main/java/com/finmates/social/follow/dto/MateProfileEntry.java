package com.finmates.social.follow.dto;

/**
 * Enriched mate row returned by {@code GET /api/internal/follows/mates?include=profile}.
 *
 * <p>Profile fields ({@code username}, {@code displayName}, {@code avatarUrl}) are looked up
 * via {@code ProfileService.getBatchSummaries} server-side so internal callers
 * (finmates-crypto) get a single round-trip rather than chaining a profile-batch
 * fetch themselves. {@code displayName} and {@code avatarUrl} are nullable per the
 * underlying {@code ProfileSummaryResponse} contract — callers fall back to
 * {@code username} or initials.</p>
 */
public record MateProfileEntry(
        Long userId,
        String username,
        String displayName,
        String avatarUrl
) {}
