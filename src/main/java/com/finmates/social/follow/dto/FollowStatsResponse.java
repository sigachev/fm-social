package com.finmates.social.follow.dto;

/**
 * Counters for a user's connection state.
 *
 * <ul>
 *   <li>{@code followers} / {@code following} — ACTIVE only (pending requests excluded).</li>
 *   <li>{@code pendingIncoming} — PENDING follow requests waiting for the user to accept.</li>
 *   <li>{@code pendingOutgoing} — PENDING follow requests the user has sent to private profiles.</li>
 *   <li>{@code mates} — count of mutual ACTIVE follows in both directions.</li>
 * </ul>
 */
public record FollowStatsResponse(
        long followers,
        long following,
        long pendingIncoming,
        long pendingOutgoing,
        long mates
) {}
