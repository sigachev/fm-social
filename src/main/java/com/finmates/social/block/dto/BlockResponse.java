package com.finmates.social.block.dto;

import java.time.OffsetDateTime;

public record BlockResponse(
        Long id,
        Long blockerId,
        Long blockedId,
        OffsetDateTime createdAt
) {}
