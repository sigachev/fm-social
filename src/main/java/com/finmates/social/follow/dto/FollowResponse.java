package com.finmates.social.follow.dto;

import com.finmates.social.follow.FollowStatus;

import java.time.OffsetDateTime;

public record FollowResponse(
        Long id,
        Long followerId,
        Long followedId,
        FollowStatus status,
        OffsetDateTime createdAt
) {}
