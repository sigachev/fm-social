package com.finmates.social.comment.dto;

import com.finmates.social.comment.CommentStatus;
import com.finmates.social.comment.CommentTargetType;

import java.time.OffsetDateTime;

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
        Long removedBy
) {}
