package com.finmates.social.connections;

import com.finmates.social.common.exception.ForbiddenActionException;
import com.finmates.social.follow.FollowRepository;
import com.finmates.social.follow.FollowStatus;
import com.finmates.social.profile.Profile;
import com.finmates.social.profile.ProfileRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single source of truth for "can viewer see this scope of content owned by target?"
 * decisions driven by the V16 {@code is_private} flag on profiles.
 *
 * <p>Decision rules:</p>
 * <ol>
 *   <li>Self-view ({@code viewerId.equals(targetId)}) always returns true.</li>
 *   <li>{@link Scope#PROFILE_BASIC} always returns true (regardless of privacy).
 *       This keeps the request-to-follow flow possible on private profiles and
 *       lets profile cards render in search results.</li>
 *   <li>If the target profile is not private, returns true.</li>
 *   <li>If the target profile is private, returns true iff viewer and target are
 *       <em>mates</em> (mutual ACTIVE follows in both directions).</li>
 *   <li>If the target profile cannot be loaded, returns false (we fail closed —
 *       a missing profile is treated as private).</li>
 * </ol>
 *
 * <p>An anonymous viewer ({@code viewerId == null}) succeeds only at {@code PROFILE_BASIC}.
 * Existing field-level filtering by {@code Profile.profileVisibility} is left untouched —
 * see {@code follow-feature-notes.md} for the consolidation roadmap.</p>
 */
@Slf4j
@Service
public class ConnectionsPermissionService {

    private final FollowRepository followRepository;
    private final ProfileRepository profileRepository;

    public ConnectionsPermissionService(FollowRepository followRepository,
                                        ProfileRepository profileRepository) {
        this.followRepository = followRepository;
        this.profileRepository = profileRepository;
    }

    /**
     * @param viewerId nullable — anonymous viewers pass only PROFILE_BASIC.
     * @param targetId required.
     * @param scope    required.
     * @return {@code true} if {@code viewerId} can see {@code scope} content owned by {@code targetId}.
     */
    @Transactional(readOnly = true)
    public boolean canView(Long viewerId, Long targetId, Scope scope) {
        if (targetId == null || scope == null) {
            return false;
        }

        // Rule 1: self-view
        if (viewerId != null && viewerId.equals(targetId)) {
            return true;
        }

        // Rule 2: PROFILE_BASIC is always allowed (so request flow works on private profiles)
        if (scope == Scope.PROFILE_BASIC) {
            return true;
        }

        // Rule 5: missing target profile fails closed
        Profile target = profileRepository.findById(targetId).orElse(null);
        if (target == null) {
            return false;
        }

        // Rule 3: public profile — anyone (including anonymous) sees the scope
        if (!target.isPrivate()) {
            return true;
        }

        // Rule 4: private profile — mates only. Anonymous viewers cannot be mates.
        if (viewerId == null) {
            return false;
        }
        return isMate(viewerId, targetId);
    }

    /**
     * Same as {@link #canView}, but throws {@link ForbiddenActionException} on denial.
     * Use in controllers/services where the caller expects to short-circuit on permission failure.
     */
    public void requireCanView(Long viewerId, Long targetId, Scope scope) {
        if (!canView(viewerId, targetId, scope)) {
            throw new ForbiddenActionException(
                    "Not allowed to view " + scope + " for user " + targetId);
        }
    }

    /**
     * True iff users {@code a} and {@code b} both have an ACTIVE follow row pointing at each other.
     * Order-independent.
     */
    @Transactional(readOnly = true)
    public boolean isMate(Long a, Long b) {
        if (a == null || b == null || a.equals(b)) {
            return false;
        }
        boolean aFollowsB = followRepository.existsByFollowerIdAndFollowedIdAndStatus(a, b, FollowStatus.ACTIVE);
        if (!aFollowsB) {
            return false;
        }
        return followRepository.existsByFollowerIdAndFollowedIdAndStatus(b, a, FollowStatus.ACTIVE);
    }
}
