package com.finmates.social.follow;

import com.finmates.social.block.BlockRepository;
import com.finmates.social.common.PageResponse;
import com.finmates.social.common.exception.ForbiddenActionException;
import com.finmates.social.common.exception.ResourceNotFoundException;
import com.finmates.social.feed.FeedService;
import com.finmates.social.follow.dto.FollowOutcome;
import com.finmates.social.follow.dto.FollowResponse;
import com.finmates.social.follow.dto.FollowStatsResponse;
import com.finmates.social.follow.dto.RelationshipResponse;
import com.finmates.social.profile.Profile;
import com.finmates.social.profile.ProfileRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class FollowService {

    private final FollowRepository followRepository;
    private final BlockRepository blockRepository;
    private final ProfileRepository profileRepository;
    private final FeedService feedService;

    public FollowService(FollowRepository followRepository,
                         BlockRepository blockRepository,
                         ProfileRepository profileRepository,
                         FeedService feedService) {
        this.followRepository = followRepository;
        this.blockRepository = blockRepository;
        this.profileRepository = profileRepository;
        this.feedService = feedService;
    }

    /**
     * Follow another user.
     *
     * <p>Idempotency: if any row already exists for {@code (followerId, followedId)} — whether
     * ACTIVE or PENDING — returns it unchanged with {@code created=false}. Re-follow is never
     * an error and never creates a duplicate row.</p>
     *
     * <p>Privacy: if the target's {@code Profile.isPrivate} is true, the row is created with
     * status {@code PENDING}; otherwise {@code ACTIVE}. Public follows fire a feed backfill
     * via {@link FeedService#onFollowActivated} so the new follower sees the followee's
     * recent posts immediately.</p>
     *
     * @return a {@link FollowOutcome} with {@code created} signalling create-vs-idempotent,
     *         used by the controller to pick {@code 201 Created} vs {@code 200 OK}.
     */
    @Transactional
    public FollowOutcome follow(Long followerId, Long followedId) {
        if (followerId.equals(followedId)) {
            throw new ForbiddenActionException("Cannot follow yourself");
        }
        if (blockRepository.existsBlockInEitherDirection(followerId, followedId)) {
            throw new ForbiddenActionException("Follow not allowed — a block exists between these users");
        }

        // Idempotency: existing row (either status) → return as-is, no DB churn.
        Optional<Follow> existing = followRepository.findByFollowerIdAndFollowedId(followerId, followedId);
        if (existing.isPresent()) {
            return new FollowOutcome(false, toResponse(existing.get()));
        }

        // Decide initial status from target profile's privacy flag. Missing profile fails closed
        // with a 404 — matches ConnectionsPermissionService's "missing target = treat as private"
        // posture, but here we surface the missing-target as an error rather than silently
        // create a follow row against a non-existent user.
        Profile target = profileRepository.findById(followedId)
                .orElseThrow(() -> new ResourceNotFoundException("Profile not found: " + followedId));
        FollowStatus initialStatus = target.isPrivate() ? FollowStatus.PENDING : FollowStatus.ACTIVE;

        Follow follow = new Follow();
        follow.setFollowerId(followerId);
        follow.setFollowedId(followedId);
        follow.setStatus(initialStatus);
        Follow saved = followRepository.save(follow);

        if (initialStatus == FollowStatus.ACTIVE) {
            feedService.onFollowActivated(followerId, followedId);
        }
        return new FollowOutcome(true, toResponse(saved));
    }

    /**
     * Unfollow / cancel-pending. Idempotent — a no-op if no row exists. The status-agnostic
     * delete handles both "stop following an active follow" and "cancel my own pending request"
     * with the same call.
     */
    @Transactional
    public void unfollow(Long followerId, Long followedId) {
        followRepository.deleteByFollowerIdAndFollowedId(followerId, followedId);
    }

    /**
     * Accept a PENDING follow request. Caller is the followee (target of the request).
     *
     * <p>Status transitions:</p>
     * <ul>
     *   <li>row not found → 404 ({@link ResourceNotFoundException}).</li>
     *   <li>row PENDING → flip to ACTIVE, save, fire feed backfill.</li>
     *   <li>row ACTIVE → 400 ({@link ResponseStatusException}). Already accepted; no-op
     *       was rejected in favor of an explicit error so the FE can detect stale UI.</li>
     * </ul>
     */
    @Transactional
    public FollowResponse accept(Long followeeId, Long followerId) {
        Follow row = followRepository.findByFollowerIdAndFollowedId(followerId, followeeId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No follow row to accept for follower=" + followerId + " followed=" + followeeId));
        if (row.getStatus() == FollowStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Follow is already ACTIVE — nothing to accept");
        }
        row.setStatus(FollowStatus.ACTIVE);
        Follow saved = followRepository.save(row);
        feedService.onFollowActivated(followerId, followeeId);
        return toResponse(saved);
    }

    /**
     * Reject a PENDING follow request. Caller is the followee.
     *
     * <p>The row is deleted entirely — there is no REJECTED status. The original requester can
     * re-request later. Rejecting an ACTIVE row returns 400 (use unfollow/block instead).</p>
     */
    @Transactional
    public void reject(Long followeeId, Long followerId) {
        Follow row = followRepository.findByFollowerIdAndFollowedId(followerId, followeeId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No follow row to reject for follower=" + followerId + " followed=" + followeeId));
        if (row.getStatus() == FollowStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Follow is ACTIVE — use unfollow or block instead of reject");
        }
        followRepository.delete(row);
    }

    /**
     * Auto-accept all PENDING incoming requests for {@code userId}. Called from
     * {@code ProfileService.updatePrivacy} when the privacy flag flips from {@code true → false}.
     *
     * <p>All status flips run in the caller's transaction (Spring propagation REQUIRED), so the
     * privacy-flag write and the bulk status flip commit atomically. Feed backfill calls run
     * inside the transaction too but Redis is non-transactional — a Redis failure logs at WARN
     * and does NOT roll back the DB transaction (matches the established post-fan-out pattern).</p>
     *
     * @return number of rows promoted from PENDING to ACTIVE.
     */
    @Transactional
    public int autoAcceptAllPending(Long userId) {
        List<Follow> pending = followRepository.findAllPendingIncoming(userId);
        if (pending.isEmpty()) {
            return 0;
        }
        for (Follow row : pending) {
            row.setStatus(FollowStatus.ACTIVE);
        }
        followRepository.saveAll(pending);
        for (Follow row : pending) {
            feedService.onFollowActivated(row.getFollowerId(), row.getFollowedId());
        }
        log.info("Auto-accepted {} pending incoming follow requests for user {}", pending.size(), userId);
        return pending.size();
    }

    /** PENDING incoming requests for {@code userId} (paginated). */
    @Transactional(readOnly = true)
    public PageResponse<FollowResponse> getPendingIncoming(Long userId, Pageable pageable) {
        return PageResponse.from(followRepository.findPendingIncoming(userId, pageable).map(this::toResponse));
    }

    /** PENDING outgoing requests sent by {@code userId} (paginated). */
    @Transactional(readOnly = true)
    public PageResponse<FollowResponse> getPendingOutgoing(Long userId, Pageable pageable) {
        return PageResponse.from(followRepository.findPendingOutgoing(userId, pageable).map(this::toResponse));
    }

    /** Aggregate counts: ACTIVE followers/following, pending in/out, mate count. */
    @Transactional(readOnly = true)
    public FollowStatsResponse getStats(Long userId) {
        long followers = followRepository.countByFollowedId(userId);
        long following = followRepository.countByFollowerId(userId);
        long pendingIncoming = followRepository.countByFollowedIdAndStatus(userId, FollowStatus.PENDING);
        long pendingOutgoing = followRepository.countByFollowerIdAndStatus(userId, FollowStatus.PENDING);
        long mates = followRepository.countMates(userId);
        return new FollowStatsResponse(followers, following, pendingIncoming, pendingOutgoing, mates);
    }

    @Transactional(readOnly = true)
    public PageResponse<FollowResponse> getFollowing(Long userId, Pageable pageable) {
        return PageResponse.from(followRepository.findByFollowerId(userId, pageable).map(this::toResponse));
    }

    @Transactional(readOnly = true)
    public PageResponse<FollowResponse> getFollowers(Long userId, Pageable pageable) {
        return PageResponse.from(followRepository.findByFollowedId(userId, pageable).map(this::toResponse));
    }

    /**
     * Per-pair relationship snapshot. {@code isFollowing} / {@code isFollowedBy} report
     * <b>ACTIVE</b> only; PENDING is reported separately so the FE can render the right
     * call-to-action ("Following", "Requested", "Follow", "Accept request").
     *
     * <p>Four bounded queries — no N+1. For list rendering, use
     * {@link FollowRepository#findMateIdsAmong} instead.</p>
     */
    @Transactional(readOnly = true)
    public RelationshipResponse getRelationship(Long viewerId, Long targetUserId) {
        boolean isFollowing = followRepository.existsByFollowerIdAndFollowedIdAndStatus(
                viewerId, targetUserId, FollowStatus.ACTIVE);
        boolean isFollowedBy = followRepository.existsByFollowerIdAndFollowedIdAndStatus(
                targetUserId, viewerId, FollowStatus.ACTIVE);
        boolean isPendingOutgoing = followRepository.existsByFollowerIdAndFollowedIdAndStatus(
                viewerId, targetUserId, FollowStatus.PENDING);
        boolean isPendingIncoming = followRepository.existsByFollowerIdAndFollowedIdAndStatus(
                targetUserId, viewerId, FollowStatus.PENDING);
        return new RelationshipResponse(isFollowing, isFollowedBy, isPendingOutgoing, isPendingIncoming);
    }

    public FollowResponse toResponse(Follow follow) {
        return new FollowResponse(
                follow.getId(),
                follow.getFollowerId(),
                follow.getFollowedId(),
                follow.getStatus(),
                follow.getCreatedAt()
        );
    }
}
