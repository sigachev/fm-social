package com.finmates.social.comment;

import java.time.Instant;

/**
 * Spring Data projection for one row of the Mentions tab query.
 *
 * <p>Native SQL exposes column types directly — {@code text[]} comes back as
 * {@link String String[]}, not {@link java.util.List List&lt;String&gt;}, because
 * the {@code @JdbcTypeCode(SqlTypes.ARRAY)} mapping on the {@link Comment}
 * entity doesn't apply to projection interfaces. The DTO mapper
 * ({@code MentionResponse.from}) normalises to {@code List<String>}.</p>
 *
 * <p><b>Timestamp types are {@link Instant}, not {@link java.time.OffsetDateTime}.</b>
 * Hibernate over native projections returns {@code Instant} for {@code TIMESTAMPTZ}
 * columns regardless of how the entity field is mapped — Spring Data's
 * projection layer has no {@code Instant → OffsetDateTime Converter} registered
 * by default, so declaring {@code OffsetDateTime} here throws
 * {@code UnsupportedOperationException: Cannot project java.time.Instant to
 * java.time.OffsetDateTime} on the first row. The on-wire DTO
 * ({@code MentionResponse}) stays {@code OffsetDateTime} (matches sibling DTOs);
 * conversion happens in {@code MentionResponse.from} via
 * {@code .atOffset(ZoneOffset.UTC)}.</p>
 *
 * <p>{@code postAuthorUsername} is null on ASSET rows (the {@code LEFT JOIN}
 * to {@code posts} produces no match) and may also be null on POST rows
 * authored before V11 (pre-V11 posts have {@code author_username = NULL}; the
 * FE falls back to {@code user_${authorId}} display per the V11 gotcha).</p>
 */
public interface MentionRow {
    Long getId();
    Long getAuthorId();
    String getAuthorUsername();
    String getTargetType();          // CommentTargetType name — "ASSET" | "POST"
    Long getTargetId();
    String getTargetSymbol();
    Long getParentId();              // always null on returned rows (top-level filter)
    String getContent();
    String getStatus();              // CommentStatus name — always "ACTIVE"
    Integer getReactionCount();
    Integer getEditCount();
    Instant getLastEditedAt();
    Instant getCreatedAt();
    Instant getUpdatedAt();
    String[] getExtractedCashtags();
    String getPostAuthorUsername();  // null on ASSET; may be null on POST (V11)
    Long getReplyCount();
    Long getReplyCountAlsoMentioning();
}
