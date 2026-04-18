package com.finmates.social.report.dto;

import com.finmates.social.report.ResolutionAction;
import com.finmates.social.report.ReportStatus;
import jakarta.validation.constraints.NotNull;

public record ReportResolveRequest(
        @NotNull ReportStatus status,
        ResolutionAction resolutionAction
) {}
