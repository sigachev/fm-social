package com.finmates.social.report;

import com.finmates.social.client.UserLookupCache;
import com.finmates.social.common.PageResponse;
import com.finmates.social.common.exception.ForbiddenActionException;
import com.finmates.social.common.exception.ResourceNotFoundException;
import com.finmates.social.post.PostRepository;
import com.finmates.social.comment.CommentRepository;
import com.finmates.social.report.dto.ReportCreateRequest;
import com.finmates.social.report.dto.ReportResolveRequest;
import com.finmates.social.report.dto.ReportResponse;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@Transactional(readOnly = true)
public class ReportService {

    private static final int DAILY_REPORT_LIMIT = 10;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final ReportRepository reportRepository;
    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final UserLookupCache userLookupCache;

    /**
     * Per-user daily report count cache — avoids a COUNT query on every submission.
     * TTL=24h, max 10_000 entries. Counts are best-effort; actual enforcement uses the DB query.
     */
    private final Cache<Long, Long> rateLimitCache = Caffeine.newBuilder()
            .expireAfterWrite(24, TimeUnit.HOURS)
            .maximumSize(10_000)
            .build();

    public ReportService(ReportRepository reportRepository,
                         PostRepository postRepository,
                         CommentRepository commentRepository,
                         UserLookupCache userLookupCache) {
        this.reportRepository = reportRepository;
        this.postRepository = postRepository;
        this.commentRepository = commentRepository;
        this.userLookupCache = userLookupCache;
    }

    @Transactional
    public ReportResponse createReport(Long reporterId, ReportCreateRequest req) {
        // Rate-limit: 10 reports per 24 hours per user
        long recentCount = reportRepository.countByReporterIdSince(
                reporterId, OffsetDateTime.now().minusHours(24));
        if (recentCount >= DAILY_REPORT_LIMIT) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Report limit reached — you can submit at most " + DAILY_REPORT_LIMIT + " reports per day");
        }

        // Dedup: one report per (reporter, target_type, target_id)
        if (reportRepository.findByReporterIdAndTargetTypeAndTargetId(
                reporterId, req.targetType(), req.targetId()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "You have already reported this content");
        }

        // Verify target exists
        validateTargetExists(req.targetType(), req.targetId());

        // Self-report prevention
        validateNotSelfReport(reporterId, req.targetType(), req.targetId());

        Report report = new Report();
        report.setReporterId(reporterId);
        report.setTargetType(req.targetType());
        report.setTargetId(req.targetId());
        report.setReason(req.reason());
        report.setDetails(req.details());

        return toResponse(reportRepository.save(report));
    }

    public PageResponse<ReportResponse> getMyReports(Long reporterId, int page, int size) {
        size = Math.min(size, 50);
        return PageResponse.from(
                reportRepository.findByReporterIdOrderByCreatedAtDesc(
                        reporterId, PageRequest.of(page, size))
                        .map(this::toResponse));
    }

    // ── Admin / internal operations ───────────────────────────────────────────

    public PageResponse<ReportResponse> getReports(ReportStatus status, ReportReason reason, int page, int size) {
        size = Math.min(size, 100);
        var pageable = PageRequest.of(page, size);
        var results = (status != null && reason != null)
                ? reportRepository.findByStatusAndReasonOrderByCreatedAtDesc(status, reason, pageable)
                : status != null
                        ? reportRepository.findByStatusOrderByCreatedAtDesc(status, pageable)
                        : reason != null
                                ? reportRepository.findByReasonOrderByCreatedAtDesc(reason, pageable)
                                : reportRepository.findAllByOrderByCreatedAtDesc(pageable);
        return PageResponse.from(results.map(this::toResponse));
    }

    public ReportResponse getReport(Long id) {
        return toResponse(findReport(id));
    }

    @Transactional
    public ReportResponse resolveReport(Long reportId, Long resolvedBy, ReportResolveRequest req) {
        Report report = findReport(reportId);
        if (report.getStatus() != ReportStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Report is already resolved");
        }
        if (req.status() == ReportStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Resolution status must be REVIEWED or DISMISSED");
        }
        report.setStatus(req.status());
        report.setResolutionAction(req.resolutionAction());
        report.setResolvedBy(resolvedBy);
        report.setResolvedAt(OffsetDateTime.now());
        return toResponse(reportRepository.save(report));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Report findReport(Long id) {
        return reportRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Report not found: " + id));
    }

    private void validateTargetExists(ReportTargetType targetType, Long targetId) {
        boolean exists = switch (targetType) {
            case POST -> postRepository.existsById(targetId);
            case COMMENT -> commentRepository.existsById(targetId);
            case USER -> true; // user existence is validated at ban time by finmates-main
        };
        if (!exists) {
            throw new ResourceNotFoundException("Target not found: " + targetType + " " + targetId);
        }
    }

    private void validateNotSelfReport(Long reporterId, ReportTargetType targetType, Long targetId) {
        if (targetType == ReportTargetType.USER && targetId.equals(reporterId)) {
            throw new ForbiddenActionException("You cannot report yourself");
        }
        if (targetType == ReportTargetType.POST) {
            postRepository.findById(targetId).ifPresent(post -> {
                if (post.getAuthorId().equals(reporterId)) {
                    throw new ForbiddenActionException("You cannot report your own post");
                }
            });
        }
        if (targetType == ReportTargetType.COMMENT) {
            commentRepository.findById(targetId).ifPresent(comment -> {
                if (comment.getAuthorId().equals(reporterId)) {
                    throw new ForbiddenActionException("You cannot report your own comment");
                }
            });
        }
    }

    public ReportResponse toResponse(Report report) {
        String reporterUsername = userLookupCache.getByUserId(report.getReporterId())
                .map(UserLookupCache.UserSummary::username)
                .orElse(null);
        return new ReportResponse(
                report.getId(),
                report.getReporterId(),
                reporterUsername,
                report.getTargetType(),
                report.getTargetId(),
                report.getReason(),
                report.getDetails(),
                report.getStatus(),
                report.getResolutionAction(),
                report.getResolvedAt(),
                report.getCreatedAt()
        );
    }
}
