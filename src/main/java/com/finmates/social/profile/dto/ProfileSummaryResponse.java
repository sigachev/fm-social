package com.finmates.social.profile.dto;

/**
 * Lightweight profile projection for batch lookups.
 *
 * <p>Intended for rendering user-card lists (Connections page, comment authors, post authors,
 * feed actors). Distinct from {@link ProfileResponse} (owner-self, all settings) and
 * {@link ProfilePublicResponse} (visitor view, full social-link bundle).
 *
 * <p>Field nullability:</p>
 * <ul>
 *   <li>{@code displayName} — null if the user has not set one. Callers should fall back to
 *       {@code username} for display, then to initials.</li>
 *   <li>{@code avatarUrl} — null if the user has no avatar. Callers should fall back to a
 *       hash-derived initial badge.</li>
 *   <li>{@code username} — null only if the {@code UserLookupCache} miss-fell-through to a
 *       finmates-main outage at batch time. UI should still render the row's other fields and
 *       let the missing username resolve on the next fetch.</li>
 *   <li>{@code isPrivate} — never null; mapped from {@code Profile.isPrivate}.</li>
 * </ul>
 *
 * <p>History: {@code username} and {@code isPrivate} added in Phase 5.0 (2026-04-27) so the
 * Connections page can render @handles and Private tags without per-row /api/profiles/{id}
 * round-trips. See {@code follow-feature-notes.md}.</p>
 */
public record ProfileSummaryResponse(
        Long userId,
        String displayName,
        String avatarUrl,
        String username,
        boolean isPrivate
) {}
