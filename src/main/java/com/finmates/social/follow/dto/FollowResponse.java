package com.finmates.social.follow.dto;

import java.time.OffsetDateTime;

public record FollowResponse(
        Long id,
        Long followerId,
        Long followedId,
        OffsetDateTime createdAt
) {}
