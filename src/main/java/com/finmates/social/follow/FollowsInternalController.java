package com.finmates.social.follow;

import com.finmates.social.follow.dto.FollowersSummaryInternalResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Internal follow-graph API for cross-service callers (finmates-main, finmates-crypto).
 *
 * <p>Secured by the existing {@code InternalSecretFilter} which validates the
 * {@code X-Internal-Secret} header on all {@code /api/internal/**} paths. No JWT required.</p>
 *
 * <p>All four endpoints are cacheable on fm-social side ({@code internal-follows} cache,
 * 60 s TTL — see {@code CacheConfig}). The TTL aligns with the main-side
 * {@code FmSocialClient} cache (cp5-c-design.md §2e). Distinct key prefixes prevent
 * collision across endpoints sharing one cache name.</p>
 *
 * <p>Empty results are 200 with an empty payload — never 404. {@code userId} missing or
 * non-numeric returns 400 via Spring's default exception handlers.</p>
 */
@RestController
@RequestMapping("/api/internal/follows")
@Tag(name = "Follows (Internal)", description = "Cross-service follow-graph API — internal use only")
public class FollowsInternalController {

    private static final int RECENT_FOLLOWERS_CAP = 50;

    private final FollowRepository followRepository;

    public FollowsInternalController(FollowRepository followRepository) {
        this.followRepository = followRepository;
    }

    @GetMapping("/mates")
    @Cacheable(value = "internal-follows", key = "'mates:' + #userId")
    @Operation(summary = "Mutual ACTIVE followers (mates) for a user")
    public Set<Long> mates(@RequestParam Long userId) {
        return followRepository.findAllMateIds(userId);
    }

    @GetMapping("/following")
    @Cacheable(value = "internal-follows", key = "'following:' + #userId")
    @Operation(summary = "All users this user actively follows")
    public Set<Long> following(@RequestParam Long userId) {
        return followRepository.findAllFollowingIds(userId);
    }

    @GetMapping("/followers")
    @Cacheable(value = "internal-follows", key = "'followers:' + #userId")
    @Operation(summary = "All users actively following this user")
    public Set<Long> followers(@RequestParam Long userId) {
        // Existing repo method returns List<Long>; wrap as Set per the design doc shape.
        // LinkedHashSet preserves the underlying scan order for determinism in tests.
        return new LinkedHashSet<>(followRepository.findAllFollowerIds(userId));
    }

    @GetMapping("/followers-summary")
    @Cacheable(value = "internal-follows", key = "'summary:' + #userId")
    @Operation(summary = "Follower count plus the N most recent followers (caller filters by time)")
    public FollowersSummaryInternalResponse followersSummary(@RequestParam Long userId) {
        long totalCount = followRepository.countByFollowedIdAndStatus(userId, FollowStatus.ACTIVE);
        Pageable cap = PageRequest.of(0, RECENT_FOLLOWERS_CAP);
        List<FollowRepository.FollowRecentView> rows = followRepository.findRecentFollowers(userId, cap);
        List<FollowersSummaryInternalResponse.RecentFollowerEntry> recent = rows.stream()
                .map(r -> new FollowersSummaryInternalResponse.RecentFollowerEntry(
                        r.getFollowerId(), r.getCreatedAt()))
                .toList();
        return new FollowersSummaryInternalResponse(totalCount, recent);
    }
}
