package com.finmates.social.report.dto;

import com.finmates.social.report.*;

import java.time.OffsetDateTime;

public record ReportResponse(
        Long id,
        Long reporterId,
        ReportTargetType targetType,
        Long targetId,
        ReportReason reason,
        String details,
        ReportStatus status,
        ResolutionAction resolutionAction,
        OffsetDateTime resolvedAt,
        OffsetDateTime createdAt
) {}
