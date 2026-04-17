package com.finmates.social.reaction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ReactionRepository extends JpaRepository<Reaction, Long> {

    @Query("SELECT r FROM Reaction r WHERE r.userId = :userId AND r.targetType = :targetType AND r.targetId = :targetId AND r.reactionType = :reactionType")
    Optional<Reaction> findByUserIdAndTargetTypeAndTargetIdAndReactionType(
            @Param("userId") Long userId,
            @Param("targetType") ReactionTargetType targetType,
            @Param("targetId") Long targetId,
            @Param("reactionType") ReactionType reactionType);

    @Query("SELECT r FROM Reaction r WHERE r.targetType = :targetType AND r.targetId = :targetId")
    List<Reaction> findByTargetTypeAndTargetId(
            @Param("targetType") ReactionTargetType targetType,
            @Param("targetId") Long targetId);

    @Query("SELECT COUNT(r) FROM Reaction r WHERE r.targetType = :targetType AND r.targetId = :targetId")
    long countByTargetTypeAndTargetId(
            @Param("targetType") ReactionTargetType targetType,
            @Param("targetId") Long targetId);
}
