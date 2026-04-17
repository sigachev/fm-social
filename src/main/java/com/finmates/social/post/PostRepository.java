package com.finmates.social.post;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
