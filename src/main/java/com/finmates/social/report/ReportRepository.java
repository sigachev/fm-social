package com.finmates.social.report;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface ReportRepository extends JpaRepository<Report, Long> {

    /** Dedup check: one report per reporter+target combination. */
    Optional<Report> findByReporterIdAndTargetTypeAndTargetId(
            Long reporterId, ReportTargetType targetType, Long targetId);

    /** Reports submitted by this user, newest first. */
    Page<Report> findByReporterIdOrderByCreatedAtDesc(Long reporterId, Pageable pageable);

    /** Admin queue: all reports filtered by status, newest first. */
    Page<Report> findByStatusOrderByCreatedAtDesc(ReportStatus status, Pageable pageable);

    /** All reports regardless of status, newest first (admin). */
    Page<Report> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Admin queue: filter by reason only, newest first. */
    Page<Report> findByReasonOrderByCreatedAtDesc(ReportReason reason, Pageable pageable);

    /** Admin queue: filter by status AND reason, newest first. */
    Page<Report> findByStatusAndReasonOrderByCreatedAtDesc(ReportStatus status, ReportReason reason, Pageable pageable);

    /** Count reports submitted by a user in the last 24 hours (rate-limit support). */
    @Query("SELECT COUNT(r) FROM Report r WHERE r.reporterId = :userId AND r.createdAt >= :since")
    long countByReporterIdSince(@Param("userId") Long userId, @Param("since") OffsetDateTime since);
}
