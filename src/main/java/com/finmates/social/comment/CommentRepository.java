package com.finmates.social.comment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    @Query("SELECT c FROM Comment c WHERE c.targetType = 'POST' AND c.targetId = :targetId AND c.status = 'ACTIVE' ORDER BY c.createdAt DESC")
    Page<Comment> findActiveByPostId(@Param("targetId") Long targetId, Pageable pageable);

    @Query("SELECT c FROM Comment c WHERE c.targetType = 'PORTFOLIO' AND c.targetId = :targetId AND c.status = 'ACTIVE' ORDER BY c.createdAt DESC")
    Page<Comment> findActiveByPortfolioOwnerId(@Param("targetId") Long targetId, Pageable pageable);

    @Query("SELECT c FROM Comment c WHERE c.targetType = 'ASSET' AND c.targetSymbol = :symbol AND c.status = 'ACTIVE' ORDER BY c.createdAt DESC")
    Page<Comment> findActiveByAssetSymbol(@Param("symbol") String symbol, Pageable pageable);

    @Modifying
    @Query("UPDATE Comment c SET c.reactionCount = c.reactionCount + :delta WHERE c.id = :id")
    void adjustReactionCount(@Param("id") Long id, @Param("delta") int delta);

    /**
     * Mentions tab: top-level ACTIVE comments cashtagging {@code currentSymbol}
     * authored on surfaces OTHER than the current asset page. v1 scope is
     * ASSET + POST targets only — PORTFOLIO comments are indexed (their
     * extracted_cashtags is populated) but not surfaced here. See Phase 2.A
     * scope decision in {@code fm-social/CLAUDE.md}.
     *
     * <p><b>Predicate form is critical for index usage.</b> Postgres's GIN
     * indexes on {@code text[]} support the containment operators
     * ({@code @>}, {@code <@}, {@code &&}), NOT {@code val = ANY(col)}.
     * {@code val = ANY(arr)} desugars to an OR-chain over equalities and
     * bypasses the GIN entirely (verified by EXPLAIN). We use
     * {@code col @> ARRAY[val]::text[]} so the planner picks
     * {@code comments_extracted_cashtags_gin}. The slice test
     * {@code ginIndexUsedForOuterScan} asserts this contract.</p>
     *
     * <p>The two correlated subqueries that compute the reply counters are
     * served by {@code idx_comments_parent} (V2). The LEFT JOIN to
     * {@code posts} populates {@code postAuthorUsername} for POST targets;
     * ASSET rows get NULL in that column and the DTO mapper ignores it.</p>
     *
     * <p>Secondary sort {@code c.id DESC} is a stable tiebreaker against
     * pagination drift when two comments share the same {@code created_at}.</p>
     */
    @Query(value = """
        SELECT c.id              AS id,
               c.author_id       AS authorId,
               c.author_username AS authorUsername,
               c.target_type     AS targetType,
               c.target_id       AS targetId,
               c.target_symbol   AS targetSymbol,
               c.parent_id       AS parentId,
               c.content         AS content,
               c.status          AS status,
               c.reaction_count  AS reactionCount,
               c.edit_count      AS editCount,
               c.last_edited_at  AS lastEditedAt,
               c.created_at      AS createdAt,
               c.updated_at      AS updatedAt,
               c.extracted_cashtags AS extractedCashtags,
               p.author_username AS postAuthorUsername,
               (SELECT COUNT(*) FROM comments r
                WHERE r.parent_id = c.id AND r.status = 'ACTIVE')      AS replyCount,
               (SELECT COUNT(*) FROM comments r
                WHERE r.parent_id = c.id
                  AND r.status = 'ACTIVE'
                  AND r.extracted_cashtags @> ARRAY[CAST(:currentSymbol AS TEXT)]) AS replyCountAlsoMentioning
        FROM comments c
        LEFT JOIN posts p ON c.target_type = 'POST' AND c.target_id = p.id
        WHERE c.extracted_cashtags @> ARRAY[CAST(:currentSymbol AS TEXT)]
          AND c.parent_id IS NULL
          AND c.status = 'ACTIVE'
          AND c.target_type IN ('ASSET', 'POST')
          AND NOT (c.target_type = 'ASSET' AND c.target_symbol = :currentSymbol)
        ORDER BY c.created_at DESC, c.id DESC
        LIMIT :limit OFFSET :offset
        """, nativeQuery = true)
    List<MentionRow> findMentionsForSymbol(
            @Param("currentSymbol") String currentSymbol,
            @Param("limit") int limit,
            @Param("offset") int offset);

    /** Total count for the same predicate as {@link #findMentionsForSymbol}. */
    @Query(value = """
        SELECT COUNT(*) FROM comments c
        WHERE c.extracted_cashtags @> ARRAY[CAST(:currentSymbol AS TEXT)]
          AND c.parent_id IS NULL
          AND c.status = 'ACTIVE'
          AND c.target_type IN ('ASSET', 'POST')
          AND NOT (c.target_type = 'ASSET' AND c.target_symbol = :currentSymbol)
        """, nativeQuery = true)
    long countMentionsForSymbol(@Param("currentSymbol") String currentSymbol);
}
