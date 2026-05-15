package com.finmates.social.comment.dto;

import com.finmates.social.comment.CommentStatus;
import com.finmates.social.comment.CommentTargetType;

import java.time.OffsetDateTime;

/**
 * Polymorphic comment response.
 *
 * <p>{@code replyCount} and {@code replyCountAlsoMentioning} are populated by
 * the Mentions endpoint
 * ({@code GET /api/discussion/token/{symbol}/mentions}); existing list
 * endpoints under {@code /api/comments/*} leave them null. Jackson serialises
 * absent {@link Long} fields as {@code null} in the JSON body so existing FE
 * consumers see no shape change.</p>
 */
public record CommentResponse(
        Long id,
        Long authorId,
        String authorUsername,
        CommentTargetType targetType,
        Long targetId,
        String targetSymbol,
        Long parentId,
        String content,
        CommentStatus status,
        int reactionCount,
        int editCount,
        OffsetDateTime lastEditedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime removedAt,
        String removalReason,
        Long removedBy,
        Long replyCount,
        Long replyCountAlsoMentioning
) {}
