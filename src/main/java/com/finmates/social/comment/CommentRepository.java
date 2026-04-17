package com.finmates.social.comment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
