package com.finmates.social.follow.dto;

import java.time.OffsetDateTime;

public record FollowerSummaryResponse(
        Long userId,
        OffsetDateTime followedAt
) {}
