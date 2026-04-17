package com.finmates.social.follow;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FollowRepository extends JpaRepository<Follow, Long> {

    @Query("SELECT f FROM Follow f WHERE f.followerId = :followerId AND f.followedId = :followedId")
    Optional<Follow> findByFollowerIdAndFollowedId(
            @Param("followerId") Long followerId,
            @Param("followedId") Long followedId);

    boolean existsByFollowerIdAndFollowedId(Long followerId, Long followedId);

    @Query("SELECT f FROM Follow f WHERE f.followerId = :followerId ORDER BY f.createdAt DESC")
    Page<Follow> findByFollowerId(@Param("followerId") Long followerId, Pageable pageable);

    @Query("SELECT f FROM Follow f WHERE f.followedId = :followedId ORDER BY f.createdAt DESC")
    Page<Follow> findByFollowedId(@Param("followedId") Long followedId, Pageable pageable);

    /**
     * Returns all IDs of users who follow the given user (followedId = target).
     * Used for feed fan-out-on-write.
     */
    @Query("SELECT f.followerId FROM Follow f WHERE f.followedId = :followedId")
    List<Long> findAllFollowerIds(@Param("followedId") Long followedId);

    long countByFollowedId(Long followedId);

    long countByFollowerId(Long followerId);

    @Modifying
    @Query("DELETE FROM Follow f WHERE f.followerId = :followerId AND f.followedId = :followedId")
    void deleteByFollowerIdAndFollowedId(@Param("followerId") Long followerId, @Param("followedId") Long followedId);

    @Modifying
    @Query("DELETE FROM Follow f WHERE (f.followerId = :userId1 AND f.followedId = :userId2) OR (f.followerId = :userId2 AND f.followedId = :userId1)")
    void deleteMutualFollows(@Param("userId1") Long userId1, @Param("userId2") Long userId2);
}
