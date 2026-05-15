package com.finmates.social.discussion;

import com.finmates.social.comment.MentionRow;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

/**
 * One row of the Mentions tab feed.
 *
 * <p>{@code targetSymbol} is populated when {@code targetType == "ASSET"} and
 * null otherwise; {@code targetId} and {@code targetAuthorUsername} are
 * populated when {@code targetType == "POST"} and null otherwise. The FE
 * uses these to render the per-mention label ("On $BTC" vs.
 * "On @bob's post") and the deep link.</p>
 *
 * <p>{@code targetAuthorUsername} may be null even on POST rows: pre-V11
 * posts have {@code posts.author_username = NULL}. The FE falls back to
 * {@code user_${authorId}} display (V11 gotcha in fm-social/CLAUDE.md).</p>
 */
public record MentionResponse(
        Long id,
        String authorUsername,
        String content,
        List<String> extractedCashtags,
        String targetType,            // "ASSET" | "POST"
        String targetSymbol,
        Long targetId,
        String targetAuthorUsername,
        Long replyCount,
        Long replyCountAlsoMentioning,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static MentionResponse from(MentionRow row) {
        boolean isAsset = "ASSET".equals(row.getTargetType());
        return new MentionResponse(
                row.getId(),
                row.getAuthorUsername(),
                row.getContent(),
                toList(row.getExtractedCashtags()),
                row.getTargetType(),
                isAsset ? row.getTargetSymbol() : null,
                isAsset ? null : row.getTargetId(),
                isAsset ? null : row.getPostAuthorUsername(),
                row.getReplyCount(),
                row.getReplyCountAlsoMentioning(),
                // MentionRow exposes Instant (Hibernate native-projection default
                // for TIMESTAMPTZ); promote to OffsetDateTime@UTC for the wire
                // shape that matches CommentResponse / PostResponse.
                toUtcOffset(row.getCreatedAt()),
                toUtcOffset(row.getUpdatedAt())
        );
    }

    private static OffsetDateTime toUtcOffset(Instant i) {
        return i == null ? null : i.atOffset(ZoneOffset.UTC);
    }

    /**
     * Normalise the {@code text[]} projection — native SQL hands it back as
     * {@code String[]} (the {@code @JdbcTypeCode(ARRAY)} on the entity field
     * doesn't apply to projection interfaces). Defensive against the row
     * coming through as null even though the column is {@code NOT NULL
     * DEFAULT '{}'}.
     */
    private static List<String> toList(String[] arr) {
        return arr == null ? List.of() : Arrays.asList(arr);
    }
}
