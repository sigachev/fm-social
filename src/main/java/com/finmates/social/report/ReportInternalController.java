package com.finmates.social.report;

import com.finmates.social.common.PageResponse;
import com.finmates.social.report.dto.ReportResolveRequest;
import com.finmates.social.report.dto.ReportResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * Internal moderation endpoints for the admin panel.
 * Secured by {@code X-Internal-Secret} header (validated by {@code InternalSecretFilter}).
 * No JWT required — callers are trusted internal services only.
 */
@RestController
@RequestMapping("/api/internal/reports")
@Tag(name = "Reports (Internal)", description = "Admin moderation queue — internal use only")
public class ReportInternalController {

    private final ReportService reportService;

    public ReportInternalController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping
    @Operation(summary = "List reports (admin) — filter by status and/or reason")
    public PageResponse<ReportResponse> getReports(
            @RequestParam(required = false) ReportStatus status,
            @RequestParam(required = false) ReportReason reason,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return reportService.getReports(status, reason, page, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get report by ID (admin)")
    public ReportResponse getReport(@PathVariable Long id) {
        return reportService.getReport(id);
    }

    @PutMapping("/{id}/resolve")
    @Operation(summary = "Resolve a report (REVIEWED or DISMISSED)")
    public ReportResponse resolveReport(
            @PathVariable Long id,
            @RequestHeader("X-Admin-User-Id") Long adminUserId,
            @Valid @RequestBody ReportResolveRequest req) {
        return reportService.resolveReport(id, adminUserId, req);
    }
}
