package com.finmates.social.post.dto;

import com.finmates.social.post.PostStatus;
import com.finmates.social.post.PostVisibility;

import java.time.OffsetDateTime;
import java.util.List;

public record PostResponse(
        Long id,
        Long authorId,
        String content,
        List<String> mediaKeys,
        PostStatus status,
        PostVisibility visibility,
        int commentCount,
        int reactionCount,
        int editCount,
        OffsetDateTime lastEditedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
