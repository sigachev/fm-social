package com.finmates.social.follow;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link Follow}.
 *
 * <p><b>Audit notes (V16):</b> several queries were updated to filter
 * {@code status = ACTIVE} so legacy callers preserve their pre-V16 semantics
 * once a PENDING state exists. New explicit-status variants (suffix
 * {@code AndStatus} or method comments) bypass the filter when callers need
 * pending rows.</p>
 *
 * <ul>
 *   <li>{@link #findByFollowerIdAndFollowedId(Long, Long)} — status-agnostic on purpose
 *       (used by service for find-then-act flows like delete and relationship probe).</li>
 *   <li>{@link #existsByFollowerIdAndFollowedId(Long, Long)} — status-agnostic on purpose
 *       (presence-check; callers interpret).</li>
 *   <li>{@link #findByFollowerId(Long, Pageable)} / {@link #findByFollowedId(Long, Pageable)}
 *       — filtered to ACTIVE so paged listings keep meaning "people I follow / who follow me".</li>
 *   <li>{@link #findAllFollowerIds(Long)} — filtered to ACTIVE so feed fan-out skips pending requests.</li>
 *   <li>{@link #countByFollowedId(Long)} / {@link #countByFollowerId(Long)} — filtered to ACTIVE so
 *       public counts continue to mean "real followers / real following".</li>
 *   <li>{@link #deleteByFollowerIdAndFollowedId(Long, Long)} — status-agnostic on purpose
 *       (handles unfollow AND cancel-pending-request in one call).</li>
 *   <li>{@link #deleteMutualFollows(Long, Long)} — status-agnostic (block-cleanup must not
 *       leave orphan pending rows).</li>
 * </ul>
 */
public interface FollowRepository extends JpaRepository<Follow, Long> {

    // ── Existence / lookup (status-agnostic — see audit notes) ─────────────────

    @Query("SELECT f FROM Follow f WHERE f.followerId = :followerId AND f.followedId = :followedId")
    Optional<Follow> findByFollowerIdAndFollowedId(
            @Param("followerId") Long followerId,
            @Param("followedId") Long followedId);

    boolean existsByFollowerIdAndFollowedId(Long followerId, Long followedId);

    /** Status-aware existence check — true only if a row of the given status exists. */
    @Query("SELECT (count(f) > 0) FROM Follow f " +
            "WHERE f.followerId = :followerId AND f.followedId = :followedId AND f.status = :status")
    boolean existsByFollowerIdAndFollowedIdAndStatus(
            @Param("followerId") Long followerId,
            @Param("followedId") Long followedId,
            @Param("status") FollowStatus status);

    // ── Listing (active-only — preserves pre-V16 semantics) ────────────────────

    @Query("SELECT f FROM Follow f WHERE f.followerId = :followerId AND f.status = com.finmates.social.follow.FollowStatus.ACTIVE ORDER BY f.createdAt DESC")
    Page<Follow> findByFollowerId(@Param("followerId") Long followerId, Pageable pageable);

    @Query("SELECT f FROM Follow f WHERE f.followedId = :followedId AND f.status = com.finmates.social.follow.FollowStatus.ACTIVE ORDER BY f.createdAt DESC")
    Page<Follow> findByFollowedId(@Param("followedId") Long followedId, Pageable pageable);

    /**
     * Returns all IDs of users actively following the given user. Used for feed fan-out-on-write.
     * PENDING rows are excluded — pending requesters do not see the followee's feed yet.
     */
    @Query("SELECT f.followerId FROM Follow f WHERE f.followedId = :followedId AND f.status = com.finmates.social.follow.FollowStatus.ACTIVE")
    List<Long> findAllFollowerIds(@Param("followedId") Long followedId);

    // ── New listing variants for the Connections feature ───────────────────────

    /**
     * Active follows where {@code userId} is the follower, optionally restricted to mates
     * (i.e. follows where the followee also follows the user back, both ACTIVE).
     */
    @Query("SELECT f FROM Follow f " +
            "WHERE f.followerId = :userId AND f.status = com.finmates.social.follow.FollowStatus.ACTIVE " +
            "ORDER BY f.createdAt DESC")
    Page<Follow> findActiveFollowing(@Param("userId") Long userId, Pageable pageable);

    @Query("SELECT f FROM Follow f " +
            "WHERE f.followerId = :userId AND f.status = com.finmates.social.follow.FollowStatus.ACTIVE " +
            "AND EXISTS (SELECT 1 FROM Follow rf WHERE rf.followerId = f.followedId " +
            "            AND rf.followedId = :userId AND rf.status = com.finmates.social.follow.FollowStatus.ACTIVE) " +
            "ORDER BY f.createdAt DESC")
    Page<Follow> findActiveFollowingMatesOnly(@Param("userId") Long userId, Pageable pageable);

    /** Active follows where {@code userId} is the followee, optionally mates-only. */
    @Query("SELECT f FROM Follow f " +
            "WHERE f.followedId = :userId AND f.status = com.finmates.social.follow.FollowStatus.ACTIVE " +
            "ORDER BY f.createdAt DESC")
    Page<Follow> findActiveFollowers(@Param("userId") Long userId, Pageable pageable);

    @Query("SELECT f FROM Follow f " +
            "WHERE f.followedId = :userId AND f.status = com.finmates.social.follow.FollowStatus.ACTIVE " +
            "AND EXISTS (SELECT 1 FROM Follow rf WHERE rf.followerId = :userId " +
            "            AND rf.followedId = f.followerId AND rf.status = com.finmates.social.follow.FollowStatus.ACTIVE) " +
            "ORDER BY f.createdAt DESC")
    Page<Follow> findActiveFollowersMatesOnly(@Param("userId") Long userId, Pageable pageable);

    /** PENDING rows where {@code userId} is the followee — i.e. requests waiting for the user to approve. */
    @Query("SELECT f FROM Follow f " +
            "WHERE f.followedId = :userId AND f.status = com.finmates.social.follow.FollowStatus.PENDING " +
            "ORDER BY f.createdAt DESC")
    Page<Follow> findPendingIncoming(@Param("userId") Long userId, Pageable pageable);

    /** PENDING rows where {@code userId} is the follower — i.e. requests the user has sent. */
    @Query("SELECT f FROM Follow f " +
            "WHERE f.followerId = :userId AND f.status = com.finmates.social.follow.FollowStatus.PENDING " +
            "ORDER BY f.createdAt DESC")
    Page<Follow> findPendingOutgoing(@Param("userId") Long userId, Pageable pageable);

    /** All PENDING rows where {@code userId} is the followee — non-paginated, used for auto-accept on privacy flip. */
    @Query("SELECT f FROM Follow f " +
            "WHERE f.followedId = :userId AND f.status = com.finmates.social.follow.FollowStatus.PENDING")
    List<Follow> findAllPendingIncoming(@Param("userId") Long userId);

    // ── Counts (active-only on the existing methods, status-aware on the new ones) ──

    @Query("SELECT count(f) FROM Follow f WHERE f.followedId = :followedId AND f.status = com.finmates.social.follow.FollowStatus.ACTIVE")
    long countByFollowedId(@Param("followedId") Long followedId);

    @Query("SELECT count(f) FROM Follow f WHERE f.followerId = :followerId AND f.status = com.finmates.social.follow.FollowStatus.ACTIVE")
    long countByFollowerId(@Param("followerId") Long followerId);

    long countByFollowerIdAndStatus(Long followerId, FollowStatus status);

    long countByFollowedIdAndStatus(Long followedId, FollowStatus status);

    /** Count of mates (mutual ACTIVE follows in both directions). */
    @Query("SELECT count(f) FROM Follow f " +
            "WHERE f.followerId = :userId AND f.status = com.finmates.social.follow.FollowStatus.ACTIVE " +
            "AND EXISTS (SELECT 1 FROM Follow rf WHERE rf.followerId = f.followedId " +
            "            AND rf.followedId = :userId AND rf.status = com.finmates.social.follow.FollowStatus.ACTIVE)")
    long countMates(@Param("userId") Long userId);

    /**
     * Bulk mate lookup: from {@code targetIds}, returns those that are mates with {@code userId}.
     * Used for rendering Mate badges across a list of users without N+1 queries.
     */
    @Query("SELECT f.followedId FROM Follow f " +
            "WHERE f.followerId = :userId AND f.status = com.finmates.social.follow.FollowStatus.ACTIVE " +
            "AND f.followedId IN :targetIds " +
            "AND EXISTS (SELECT 1 FROM Follow rf WHERE rf.followerId = f.followedId " +
            "            AND rf.followedId = :userId AND rf.status = com.finmates.social.follow.FollowStatus.ACTIVE)")
    List<Long> findMateIdsAmong(@Param("userId") Long userId, @Param("targetIds") Collection<Long> targetIds);

    // ── Deletes (status-agnostic — see audit notes) ────────────────────────────

    @Modifying
    @Query("DELETE FROM Follow f WHERE f.followerId = :followerId AND f.followedId = :followedId")
    void deleteByFollowerIdAndFollowedId(@Param("followerId") Long followerId, @Param("followedId") Long followedId);

    @Modifying
    @Query("DELETE FROM Follow f WHERE (f.followerId = :userId1 AND f.followedId = :userId2) OR (f.followerId = :userId2 AND f.followedId = :userId1)")
    void deleteMutualFollows(@Param("userId1") Long userId1, @Param("userId2") Long userId2);
}
