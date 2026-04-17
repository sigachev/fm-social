package com.finmates.social.follow.dto;

public record RelationshipResponse(
        boolean isFollowing,
        boolean isFollowedBy
) {}
