package com.finmates.social.follow;

import com.finmates.social.follow.dto.FollowersSummaryInternalResponse;
import com.finmates.social.follow.dto.MateProfileEntry;
import com.finmates.social.profile.ProfileService;
import com.finmates.social.profile.dto.ProfileSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    private final ProfileService profileService;

    public FollowsInternalController(FollowRepository followRepository,
                                     ProfileService profileService) {
        this.followRepository = followRepository;
        this.profileService = profileService;
    }

    @GetMapping(value = "/mates", params = "!include")
    @Cacheable(value = "internal-follows", key = "'mates:' + #userId")
    @Operation(summary = "Mutual ACTIVE followers (mates) for a user — IDs only")
    public Set<Long> mates(@RequestParam Long userId) {
        return followRepository.findAllMateIds(userId);
    }

    /**
     * Enriched variant of {@link #mates(Long)} that joins the mate IDs with
     * profile summaries server-side ({@code ProfileService.getBatchSummaries}).
     *
     * <p>Internal callers (finmates-crypto Network Pulse / mates-pulse pipeline)
     * use this to avoid a second round-trip per mate. {@code displayName} and
     * {@code avatarUrl} are nullable per the underlying
     * {@link ProfileSummaryResponse} contract.</p>
     *
     * <p>IDs whose profile is missing (cache miss → main outage) still appear
     * in the response with {@code username} / {@code displayName} / {@code avatarUrl}
     * = null — callers fall back to initials. The mate ID itself is authoritative.</p>
     */
    @GetMapping(value = "/mates", params = "include=profile")
    @Cacheable(value = "internal-follows", key = "'mates:profile:' + #userId")
    @Operation(summary = "Mutual ACTIVE followers (mates) for a user — with profile summary")
    public List<MateProfileEntry> matesWithProfile(@RequestParam Long userId,
                                                    @RequestParam("include") String include) {
        // The `include` param is required by the routing condition above; ignored here.
        // Spring routes the no-param variant via params="!include", so this branch
        // only executes when include=profile.
        Set<Long> mateIds = followRepository.findAllMateIds(userId);
        if (mateIds.isEmpty()) return List.of();

        List<ProfileSummaryResponse> summaries =
                profileService.getBatchSummaries(new ArrayList<>(mateIds));
        Map<Long, ProfileSummaryResponse> byUserId = summaries.stream()
                .collect(Collectors.toMap(
                        ProfileSummaryResponse::userId,
                        Function.identity(),
                        (a, b) -> a));

        // Preserve the iteration order of the underlying mate-id query for
        // deterministic responses (LinkedHashSet does this; HashSet would not).
        List<MateProfileEntry> out = new ArrayList<>(mateIds.size());
        for (Long id : mateIds) {
            ProfileSummaryResponse s = byUserId.get(id);
            if (s == null) {
                out.add(new MateProfileEntry(id, null, null, null));
            } else {
                out.add(new MateProfileEntry(id, s.username(), s.displayName(), s.avatarUrl()));
            }
        }
        return out;
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
