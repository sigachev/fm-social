package com.finmates.social.post;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostRepository extends JpaRepository<Post, Long> {

    @Query("SELECT p FROM Post p WHERE p.authorId = :authorId AND p.status = 'ACTIVE' ORDER BY p.createdAt DESC")
    Page<Post> findActiveByAuthorId(@Param("authorId") Long authorId, Pageable pageable);

    @Modifying
    @Query("UPDATE Post p SET p.commentCount = p.commentCount + :delta WHERE p.id = :id")
    void adjustCommentCount(@Param("id") Long id, @Param("delta") int delta);

    @Modifying
    @Query("UPDATE Post p SET p.reactionCount = p.reactionCount + :delta WHERE p.id = :id")
    void adjustReactionCount(@Param("id") Long id, @Param("delta") int delta);
}
