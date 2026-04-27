package com.finmates.social.follow.dto;

/**
 * Symmetric relationship snapshot between viewer and target.
 *
 * <p>{@code isFollowing} / {@code isFollowedBy} reflect <b>ACTIVE</b> follows only — a
 * pending request from viewer to target does NOT make {@code isFollowing == true}. The
 * pending state is reported separately via {@code isPendingOutgoing} (viewer requested
 * to follow target) and {@code isPendingIncoming} (target requested to follow viewer).</p>
 */
public record RelationshipResponse(
        boolean isFollowing,
        boolean isFollowedBy,
        boolean isPendingOutgoing,
        boolean isPendingIncoming
) {}

