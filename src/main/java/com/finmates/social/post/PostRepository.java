package com.finmates.social.post;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface PostRepository extends JpaRepository<Post, Long> {

    /** Page-based query — used internally by getPostsByUser. */
    @Query("SELECT p FROM Post p WHERE p.authorId = :authorId AND p.status = 'ACTIVE' ORDER BY p.createdAt DESC")
    Page<Post> findActiveByAuthorId(@Param("authorId") Long authorId, Pageable pageable);

    /** Cursor-based: first page — ordered by id DESC for stable keyset pagination. */
    @Query("SELECT p FROM Post p WHERE p.authorId = :authorId AND p.status = 'ACTIVE' ORDER BY p.id DESC")
    List<Post> findActiveByAuthorIdDesc(@Param("authorId") Long authorId, Pageable pageable);

    /** Cursor-based: subsequent pages — only posts with id strictly less than the cursor. */
    @Query("SELECT p FROM Post p WHERE p.authorId = :authorId AND p.status = 'ACTIVE' AND p.id < :cursor ORDER BY p.id DESC")
    List<Post> findActiveByAuthorIdBeforeCursor(@Param("authorId") Long authorId,
                                                @Param("cursor") Long cursor,
                                                Pageable pageable);

    long countByAuthorIdAndStatus(Long authorId, PostStatus status);

    @Modifying
    @Query("UPDATE Post p SET p.commentCount = p.commentCount + :delta WHERE p.id = :id")
    void adjustCommentCount(@Param("id") Long id, @Param("delta") int delta);

    @Modifying
    @Query("UPDATE Post p SET p.reactionCount = p.reactionCount + :delta WHERE p.id = :id")
    void adjustReactionCount(@Param("id") Long id, @Param("delta") int delta);

    // ── Cashtag search (native SQL — engagement-weighted sort) ──────────────
    //
    // No post_cashtags join table or tsvector index exists (cashtags live as
    // plain text in posts.content). This is the agreed-upon scale-bounded
    // mitigation; a post_cashtags migration is tracked as follow-up work in
    // CLAUDE.md. The :pattern parameter is built by the service as a quoted
    // form like '%$btc%' (lowercase) and matched against LOWER(content) for
    // case-insensitive search anchored on the cashtag '$' prefix.
    //
    // Engagement-weighted sort: for posts created in the last 24h, score is
    // (reaction_count + comment_count * 2). Older posts score 0 and fall
    // through to createdAt DESC. JPQL CASE+INTERVAL is awkward across
    // dialects — native PostgreSQL is the right call here and matches other
    // native methods in this codebase (BlockRepository.existsBlockInEitherDirection).

    @Query(value = """
            SELECT * FROM posts
            WHERE status = 'ACTIVE'
              AND LOWER(content) LIKE :pattern
            ORDER BY
              CASE WHEN created_at >= NOW() - INTERVAL '24 hours'
                   THEN (reaction_count + comment_count * 2)
                   ELSE 0
              END DESC,
              created_at DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Post> findByCashtagGlobal(@Param("pattern") String pattern,
                                   @Param("limit") int limit);

    @Query(value = """
            SELECT * FROM posts
            WHERE status = 'ACTIVE'
              AND LOWER(content) LIKE :pattern
              AND author_id IN (:authorIds)
            ORDER BY
              CASE WHEN created_at >= NOW() - INTERVAL '24 hours'
                   THEN (reaction_count + comment_count * 2)
                   ELSE 0
              END DESC,
              created_at DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Post> findByCashtagNetwork(@Param("pattern") String pattern,
                                    @Param("authorIds") Collection<Long> authorIds,
                                    @Param("limit") int limit);
}
