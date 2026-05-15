package com.finmates.social.comment;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "comments")
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "author_id", nullable = false)
    private Long authorId;

    @Column(name = "author_username", nullable = false, length = 64)
    private String authorUsername;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    private CommentTargetType targetType;

    @Column(name = "target_id")
    private Long targetId;

    @Column(name = "target_symbol", length = 20)
    private String targetSymbol;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    /**
     * Canonical (uppercase, sorted, deduplicated) cashtag symbols extracted
     * from {@link #content} at write time by
     * {@link com.finmates.social.util.CashtagExtractor}. Backs the GIN-indexed
     * Mentions tab query introduced in Phase 2.
     *
     * <p>Replacement semantics: every create/update overwrites this list with
     * a fresh extraction — never an append. Field default ensures new entities
     * never serialise {@code null} even if the extractor is bypassed in tests.</p>
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "extracted_cashtags", columnDefinition = "text[]", nullable = false)
    private List<String> extractedCashtags = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CommentStatus status = CommentStatus.ACTIVE;

    @Column(name = "reaction_count", nullable = false)
    private int reactionCount = 0;

    @Column(name = "edit_count", nullable = false)
    private int editCount = 0;

    @Column(name = "last_edited_at")
    private OffsetDateTime lastEditedAt;

    /** Set when status transitions to REMOVED via admin moderation action. */
    @Column(name = "removed_at")
    private OffsetDateTime removedAt;

    /** ID of the admin user who removed this comment. */
    @Column(name = "removed_by")
    private Long removedBy;

    @Column(name = "removal_reason", length = 500)
    private String removalReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
