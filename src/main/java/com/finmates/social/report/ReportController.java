package com.finmates.social.report;

import com.finmates.social.common.PageResponse;
import com.finmates.social.common.security.AuthenticatedUser;
import com.finmates.social.report.dto.ReportCreateRequest;
import com.finmates.social.report.dto.ReportResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reports")
@PreAuthorize("isAuthenticated()")
@Tag(name = "Reports", description = "User content reports")
public class ReportController {

    private final ReportService reportService;
    private final AuthenticatedUser authenticatedUser;

    public ReportController(ReportService reportService, AuthenticatedUser authenticatedUser) {
        this.reportService = reportService;
        this.authenticatedUser = authenticatedUser;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Submit a content report")
    @ApiResponse(responseCode = "201", description = "Report submitted")
    @ApiResponse(responseCode = "409", description = "Already reported this content")
    @ApiResponse(responseCode = "429", description = "Daily report limit reached")
    public ReportResponse createReport(@Valid @RequestBody ReportCreateRequest req) {
        Long userId = authenticatedUser.currentUserId();
        return reportService.createReport(userId, req);
    }

    @GetMapping("/mine")
    @Operation(summary = "Get reports submitted by the current user")
    @ApiResponse(responseCode = "200", description = "Paginated list of own reports")
    public PageResponse<ReportResponse> getMyReports(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = authenticatedUser.currentUserId();
        return reportService.getMyReports(userId, page, size);
    }
}
