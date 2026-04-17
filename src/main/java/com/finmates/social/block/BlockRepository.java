package com.finmates.social.block;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BlockRepository extends JpaRepository<Block, Long> {

    @Query("SELECT b FROM Block b WHERE b.blockerId = :blockerId AND b.blockedId = :blockedId")
    Optional<Block> findByBlockerIdAndBlockedId(
            @Param("blockerId") Long blockerId,
            @Param("blockedId") Long blockedId);

    boolean existsByBlockerIdAndBlockedId(Long blockerId, Long blockedId);

    @Query("SELECT b FROM Block b WHERE b.blockerId = :blockerId ORDER BY b.createdAt DESC")
    Page<Block> findByBlockerId(@Param("blockerId") Long blockerId, Pageable pageable);

    @Query("SELECT COUNT(b) > 0 FROM Block b WHERE (b.blockerId = :userId1 AND b.blockedId = :userId2) OR (b.blockerId = :userId2 AND b.blockedId = :userId1)")
    boolean existsBlockInEitherDirection(@Param("userId1") Long userId1, @Param("userId2") Long userId2);
}
