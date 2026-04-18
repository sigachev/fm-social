package com.finmates.social.profile.dto;

/**
 * Lightweight profile projection for batch lookups.
 *
 * <p>Intended for rendering author avatars and names in lists (comments, posts, feeds).
 * Excludes username — callers have it from the parent entity (e.g. {@code Comment.authorUsername}).
 * Excludes all privacy flags, social links, and visibility settings — those are in
 * {@link ProfileResponse} (owner) or {@link ProfilePublicResponse} (visitor).
 *
 * <p>{@code displayName} may be null if the user has not set one.
 * {@code avatarUrl} may be null if the user has no avatar; callers should fall back to initials.
 */
public record ProfileSummaryResponse(
        Long userId,
        String displayName,
        String avatarUrl
) {}
