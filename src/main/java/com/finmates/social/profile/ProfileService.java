package com.finmates.social.profile;

import com.finmates.social.block.BlockRepository;
import com.finmates.social.client.PortfolioSummaryResponse;
import com.finmates.social.client.UserLookupCache;
import com.finmates.social.common.exception.ForbiddenActionException;
import com.finmates.social.common.exception.ResourceNotFoundException;
import com.finmates.social.follow.FollowRepository;
import com.finmates.social.follow.FollowService;
import com.finmates.social.follow.FollowStatus;
import com.finmates.social.post.PostRepository;
import com.finmates.social.post.PostStatus;
import com.finmates.social.profile.dto.PrivacyUpdateResponse;
import com.finmates.social.profile.dto.ProfilePublicResponse;
import com.finmates.social.profile.dto.ProfileResponse;
import com.finmates.social.profile.dto.ProfileUpdateRequest;
import com.finmates.social.profile.dto.PublicProfileResponse;
import com.finmates.social.upload.S3Service;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@Transactional(readOnly = true)
public class ProfileService {

    private final ProfileRepository profileRepository;
    private final FollowRepository followRepository;
    private final FollowService followService;
    private final BlockRepository blockRepository;
    private final PostRepository postRepository;
    private final S3Service s3Service;
    private final UserLookupCache userLookupCache;
    private final WebClient cryptoServiceWebClient;

    public ProfileService(ProfileRepository profileRepository,
                          FollowRepository followRepository,
                          FollowService followService,
                          BlockRepository blockRepository,
                          PostRepository postRepository,
                          S3Service s3Service,
                          UserLookupCache userLookupCache,
                          @Qualifier("cryptoServiceWebClient") WebClient cryptoServiceWebClient) {
        this.profileRepository = profileRepository;
        this.followRepository = followRepository;
        this.followService = followService;
        this.blockRepository = blockRepository;
        this.postRepository = postRepository;
        this.s3Service = s3Service;
        this.userLookupCache = userLookupCache;
        this.cryptoServiceWebClient = cryptoServiceWebClient;
    }

    @Cacheable(value = "profileCache", key = "#userId")
    public Profile getProfile(Long userId) {
        return profileRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Profile not found for user: " + userId));
    }

    public ProfileResponse getOwnProfile(Long userId) {
        return toFullResponse(getProfile(userId));
    }

    @Transactional
    @CacheEvict(value = "profileCache", key = "#userId")
    public ProfileResponse updateProfile(Long userId, ProfileUpdateRequest req) {
        // Fetch directly from repo (not via cached getProfile) since we're modifying
        Profile profile = profileRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Profile not found for user: " + userId));

        // ── Avatar S3 promotion ──────────────────────────────────────────────
        if (req.getAvatarKey() != null) {
            String folder = "users/" + userId + "/profile";
            s3Service.validateOwnership(userId, req.getAvatarKey(), folder);
            if (!s3Service.objectExists(req.getAvatarKey())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Avatar not yet uploaded to S3");
            }
            // Delete old avatar key (best-effort — don't fail the update if deletion fails)
            if (profile.getAvatarKey() != null) {
                s3Service.delete(profile.getAvatarKey());
            }
            String filename = req.getAvatarKey().substring(req.getAvatarKey().lastIndexOf('/') + 1);
            String finalKey = folder + "/" + filename;
            s3Service.copy(req.getAvatarKey(), finalKey);
            profile.setAvatarKey(finalKey);
            profile.setProfileImageUrl(null); // enforce avatar_key_exclusive DB constraint
            s3Service.delete(req.getAvatarKey()); // delete pending copy
        }

        // ── Cover S3 promotion ───────────────────────────────────────────────
        if (req.getCoverKey() != null) {
            String folder = "users/" + userId + "/cover";
            s3Service.validateOwnership(userId, req.getCoverKey(), folder);
            if (!s3Service.objectExists(req.getCoverKey())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Cover image not yet uploaded to S3");
            }
            if (profile.getCoverKey() != null) {
                s3Service.delete(profile.getCoverKey());
            }
            String filename = req.getCoverKey().substring(req.getCoverKey().lastIndexOf('/') + 1);
            String finalKey = folder + "/" + filename;
            s3Service.copy(req.getCoverKey(), finalKey);
            profile.setCoverKey(finalKey);
            s3Service.delete(req.getCoverKey()); // delete pending copy
        }

        applyUpdates(profile, req);
        return toFullResponse(profileRepository.save(profile));
    }

    /**
     * Returns a visibility-filtered profile for the given viewer.
     * viewerId may be null for unauthenticated requests (PRIVATE profiles will be rejected).
     */
    public ProfilePublicResponse getPublicProfile(Long viewerId, Long targetUserId) {
        Profile profile = getProfile(targetUserId);

        // Owner always gets full visibility
        if (viewerId != null && viewerId.equals(targetUserId)) {
            return toFilteredPublicResponse(profile, true, true);
        }

        return switch (profile.getProfileVisibility()) {
            case PRIVATE -> throw new ForbiddenActionException("This profile is private");
            case FOLLOWERS -> {
                // V16: PENDING requesters must NOT yet see FOLLOWERS-mode profile fields.
                // Full consolidation onto ConnectionsPermissionService is Phase 3.
                boolean isFollowing = viewerId != null &&
                        followRepository.existsByFollowerIdAndFollowedIdAndStatus(
                                viewerId, targetUserId, FollowStatus.ACTIVE);
                yield toFilteredPublicResponse(profile, isFollowing, isFollowing);
            }
            case PUBLIC -> toFilteredPublicResponse(profile, true, true);
        };
    }

    /**
     * Toggle the connection-privacy flag on the caller's profile.
     *
     * <p>Privacy semantics:</p>
     * <ul>
     *   <li><b>{@code true → false}</b> (going public): all PENDING incoming follow requests
     *       are auto-accepted in the same transaction, and each promoted relationship fires a
     *       feed backfill so the new follower sees the caller's recent posts immediately.</li>
     *   <li><b>{@code false → true}</b> (going private): existing ACTIVE follows are
     *       intentionally NOT touched. Only <em>new</em> follow attempts will be created with
     *       status PENDING. This is by design — flipping to private should not silently
     *       disconnect users who already have an established relationship; if the caller
     *       wants that, they must remove followers explicitly.</li>
     *   <li>No-op flips (true→true, false→false) write nothing and return autoAcceptedCount=0.</li>
     * </ul>
     *
     * <p>Single transactional op: the privacy-flag write and the bulk PENDING→ACTIVE flip both
     * commit atomically (Spring propagation REQUIRED through {@link FollowService#autoAcceptAllPending}).
     * The Redis feed-backfill calls run inside the same method but are best-effort — Redis failure
     * does NOT roll back the DB transaction (matches the established post-fan-out pattern).</p>
     */
    @Transactional
    @CacheEvict(value = "profileCache", key = "#userId")
    public PrivacyUpdateResponse updatePrivacy(Long userId, boolean newIsPrivate) {
        Profile profile = profileRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Profile not found for user: " + userId));

        boolean wasPrivate = profile.isPrivate();
        if (wasPrivate == newIsPrivate) {
            return new PrivacyUpdateResponse(newIsPrivate, 0);
        }

        profile.setPrivate(newIsPrivate);
        profileRepository.save(profile);

        int autoAccepted = 0;
        if (wasPrivate && !newIsPrivate) {
            // private → public: auto-accept everything that was waiting on approval.
            autoAccepted = followService.autoAcceptAllPending(userId);
        }
        // public → private: no retroactive change to existing ACTIVE follows.
        // Only new follow attempts (handled in FollowService.follow) will go to PENDING from now on.

        return new PrivacyUpdateResponse(newIsPrivate, autoAccepted);
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private void applyUpdates(Profile profile, ProfileUpdateRequest req) {
        // Note: avatarKey and coverKey are handled by S3 promotion above — not set here.
        if (req.getBio() != null)                      profile.setBio(req.getBio());
        if (req.getDisplayName() != null)              profile.setDisplayName(req.getDisplayName());
        if (req.getFirstName() != null)                profile.setFirstName(req.getFirstName());
        if (req.getLastName() != null)                 profile.setLastName(req.getLastName());
        if (req.getNameDisplayPreference() != null)    profile.setNameDisplayPreference(req.getNameDisplayPreference());
        // OAuth avatar URL: mutually exclusive with avatarKey (DB constraint avatar_key_exclusive)
        if (req.getProfileImageUrl() != null) {
            profile.setProfileImageUrl(req.getProfileImageUrl());
            profile.setAvatarKey(null); // enforce mutual exclusion
        }
        if (req.getLocation() != null)                 profile.setLocation(req.getLocation());
        if (req.getWebsite() != null)                  profile.setWebsite(req.getWebsite());
        if (req.getTimezone() != null)                 profile.setTimezone(req.getTimezone());
        if (req.getTwitterHandle() != null)            profile.setTwitterHandle(req.getTwitterHandle());
        if (req.getDiscordHandle() != null)            profile.setDiscordHandle(req.getDiscordHandle());
        if (req.getTelegramHandle() != null)           profile.setTelegramHandle(req.getTelegramHandle());
        if (req.getInstagramHandle() != null)          profile.setInstagramHandle(req.getInstagramHandle());
        if (req.getFacebookHandle() != null)           profile.setFacebookHandle(req.getFacebookHandle());
        if (req.getLinkedinHandle() != null)           profile.setLinkedinHandle(req.getLinkedinHandle());
        if (req.getWhatsappHandle() != null)           profile.setWhatsappHandle(req.getWhatsappHandle());
        if (req.getProfileVisibility() != null)        profile.setProfileVisibility(req.getProfileVisibility());
        if (req.getPortfolioVisibility() != null)      profile.setPortfolioVisibility(req.getPortfolioVisibility());
        if (req.getShowPnl() != null)                  profile.setShowPnl(req.getShowPnl());
        if (req.getShowPositions() != null)            profile.setShowPositions(req.getShowPositions());
        if (req.getShowLocation() != null)             profile.setShowLocation(req.getShowLocation());
        if (req.getShowRealName() != null)             profile.setShowRealName(req.getShowRealName());
        if (req.getShowTradingActivity() != null)      profile.setShowTradingActivity(req.getShowTradingActivity());
        if (req.getShowTradeHistory() != null)         profile.setShowTradeHistory(req.getShowTradeHistory());
        if (req.getShowOnlineStatus() != null)         profile.setShowOnlineStatus(req.getShowOnlineStatus());
        if (req.getShowInLeaderboard() != null)        profile.setShowInLeaderboard(req.getShowInLeaderboard());
        if (req.getAllowFollowers() != null)            profile.setAllowFollowers(req.getAllowFollowers());
        if (req.getAllowCopyTrading() != null)          profile.setAllowCopyTrading(req.getAllowCopyTrading());
        if (req.getNotesPermission() != null)          profile.setNotesPermission(req.getNotesPermission());
        if (req.getSignalCommentsPermission() != null) profile.setSignalCommentsPermission(req.getSignalCommentsPermission());
        if (req.getAllowMessages() != null)             profile.setAllowMessages(req.getAllowMessages());
    }

    /**
     * Full response for the profile owner. Generates presigned GET URLs for S3 keys;
     * falls back to OAuth URL when the S3 key is absent.
     */
    public ProfileResponse toFullResponse(Profile p) {
        String avatarUrl = resolveAvatarUrl(p);
        String coverUrl  = p.getCoverKey() != null ? s3Service.createPresignedGet(p.getCoverKey()) : null;

        return new ProfileResponse(
                p.getUserId(), p.getBio(), p.getDisplayName(), p.getFirstName(), p.getLastName(),
                p.getNameDisplayPreference(),
                avatarUrl, coverUrl,
                p.getLocation(), p.getWebsite(), p.getTimezone(),
                p.getTwitterHandle(), p.getDiscordHandle(), p.getTelegramHandle(),
                p.getInstagramHandle(), p.getFacebookHandle(), p.getLinkedinHandle(), p.getWhatsappHandle(),
                p.getProfileVisibility(), p.getPortfolioVisibility(),
                p.isShowPnl(), p.isShowPositions(), p.isShowLocation(), p.isShowRealName(),
                p.isShowTradingActivity(), p.isShowTradeHistory(), p.isShowOnlineStatus(),
                p.isShowInLeaderboard(), p.isAllowFollowers(), p.isAllowCopyTrading(),
                p.getNotesPermission(), p.getSignalCommentsPermission(), p.getAllowMessages(),
                p.getCreatedAt(), p.getUpdatedAt()
        );
    }

    /**
     * @param showAllFields    viewer has follower-level access (bio, cover, real name, location)
     * @param showSocialHandles viewer has follower-level access (social handles)
     */
    private ProfilePublicResponse toFilteredPublicResponse(Profile p,
                                                            boolean showAllFields,
                                                            boolean showSocialHandles) {
        String avatarUrl = resolveAvatarUrl(p);
        String coverUrl  = showAllFields && p.getCoverKey() != null
                ? s3Service.createPresignedGet(p.getCoverKey()) : null;

        return new ProfilePublicResponse(
                p.getUserId(),
                p.getDisplayName(),
                showAllFields && p.isShowRealName() ? p.getFirstName() : null,
                showAllFields && p.isShowRealName() ? p.getLastName()  : null,
                avatarUrl,
                coverUrl,
                showAllFields ? p.getBio() : null,
                showAllFields && p.isShowLocation() ? p.getLocation() : null,
                showAllFields ? p.getWebsite()  : null,
                showAllFields ? p.getTimezone() : null,
                showSocialHandles ? p.getTwitterHandle()   : null,
                showSocialHandles ? p.getDiscordHandle()   : null,
                showSocialHandles ? p.getTelegramHandle()  : null,
                showSocialHandles ? p.getInstagramHandle() : null,
                showSocialHandles ? p.getFacebookHandle()  : null,
                showSocialHandles ? p.getLinkedinHandle()  : null,
                showSocialHandles ? p.getWhatsappHandle()  : null,
                p.getProfileVisibility(),
                p.getPortfolioVisibility(),
                p.isShowPnl(), p.isShowPositions(), p.isShowTradingActivity(),
                p.isShowTradeHistory(), p.isShowOnlineStatus(), p.isShowInLeaderboard(),
                p.getCreatedAt()
        );
    }

    /**
     * Builds the full public profile response including stats and user summary.
     * Returns empty Optional if profile not found, is private (and viewer is not owner), or blocked.
     *
     * @param viewerId    viewer's userId — may be null for unauthenticated requests
     * @param targetUserId the profile owner's userId
     * @param userSummary auth-state summary resolved from finmates-main
     */
    public Optional<PublicProfileResponse> buildPublicProfileResponse(
            Long viewerId,
            Long targetUserId,
            UserLookupCache.UserSummary userSummary) {

        Optional<Profile> profileOpt = profileRepository.findById(targetUserId);
        if (profileOpt.isEmpty()) return Optional.empty();
        Profile profile = profileOpt.get();

        // Privacy check — owner always passes
        if (profile.getProfileVisibility() == ProfileVisibility.PRIVATE
                && !targetUserId.equals(viewerId)) {
            return Optional.empty();
        }

        // Block check (both directions)
        if (viewerId != null && blockRepository.existsBlockInEitherDirection(viewerId, targetUserId)) {
            return Optional.empty();
        }

        // Stats
        long postsCount = postRepository.countByAuthorIdAndStatus(targetUserId, PostStatus.ACTIVE);
        long followersCount = followRepository.countByFollowedId(targetUserId);
        long followingCount = followRepository.countByFollowerId(targetUserId);

        // V16: PENDING requesters must NOT yet see FOLLOWERS-mode profile fields.
        // Full consolidation onto ConnectionsPermissionService is Phase 3.
        boolean isFollowing = viewerId != null
                && followRepository.existsByFollowerIdAndFollowedIdAndStatus(
                        viewerId, targetUserId, FollowStatus.ACTIVE);
        boolean isBlocked = viewerId != null
                && blockRepository.existsBlockInEitherDirection(viewerId, targetUserId);

        String avatarUrl = resolveAvatarUrl(profile);
        String coverUrl = profile.getCoverKey() != null
                ? s3Service.createPresignedGet(profile.getCoverKey()) : null;

        String displayName = resolveDisplayName(profile);

        PortfolioSummaryResponse portfolio = null;
        try {
            portfolio = cryptoServiceWebClient.get()
                    .uri("/api/internal/portfolios/user/{id}/summary", targetUserId)
                    .retrieve()
                    .bodyToMono(PortfolioSummaryResponse.class)
                    .timeout(Duration.ofSeconds(3))
                    .onErrorResume(e -> {
                        if (e instanceof org.springframework.web.reactive.function.client.WebClientResponseException.NotFound) {
                            log.debug("No portfolio data for userId={} (404 — no trading activity)", targetUserId);
                        } else {
                            log.warn("Portfolio lookup failed for userId={}: {}", targetUserId, e.getMessage());
                        }
                        return Mono.empty();
                    })
                    .blockOptional()
                    .orElse(null);
        } catch (Exception e) {
            log.warn("Portfolio lookup exception for userId={}: {}", targetUserId, e.getMessage());
        }

        return Optional.of(new PublicProfileResponse(
                targetUserId,
                userSummary.username(),
                displayName,
                profile.getBio(),
                avatarUrl,
                coverUrl,
                new PublicProfileResponse.Stats(postsCount, followersCount, followingCount),
                portfolio,
                userSummary.emailVerified(),
                isFollowing,
                isBlocked
        ));
    }

    private String resolveDisplayName(Profile p) {
        if (p.getDisplayName() != null && !p.getDisplayName().isBlank()) return p.getDisplayName();
        if (p.getFirstName() != null) {
            return p.getLastName() != null
                    ? p.getFirstName() + " " + p.getLastName()
                    : p.getFirstName();
        }
        return null;
    }

    /**
     * Returns lightweight profile summaries for the given user IDs.
     *
     * <p>IDs with no profile row are silently omitted — callers must tolerate missing entries
     * (e.g. deleted users) and fall back to initials. Response order is NOT guaranteed to match
     * input order; build a {@code Map<Long, ProfileSummaryResponse>} for lookup.
     *
     * @param userIds up to 200 user IDs (duplicates are deduplicated)
     */
    public List<com.finmates.social.profile.dto.ProfileSummaryResponse> getBatchSummaries(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        List<Long> distinctIds = userIds.stream().distinct().toList();
        List<Profile> profiles = profileRepository.findAllById(distinctIds);
        return profiles.stream()
                .map(p -> new com.finmates.social.profile.dto.ProfileSummaryResponse(
                        p.getUserId(),
                        p.getDisplayName(),
                        resolveAvatarUrl(p),
                        // Phase 5.0: per-id loop against UserLookupCache (5-min Caffeine TTL,
                        // 10k entries). Cache hits are free; misses fall through to
                        // finmates-main /internal/users/{id}/summary — same path the existing
                        // ProfileController.getPublicProfileByUsername already uses. No bulk
                        // lookup helper exists yet; for batches up to 200 the loop is fine.
                        userLookupCache.getByUserId(p.getUserId())
                                .map(com.finmates.social.client.UserLookupCache.UserSummary::username)
                                .orElse(null),
                        p.isPrivate()
                ))
                .toList();
    }

    /** Presigned S3 URL if avatarKey is set; external OAuth URL otherwise; null if neither. */
    private String resolveAvatarUrl(Profile p) {
        if (p.getAvatarKey() != null) return s3Service.createPresignedGet(p.getAvatarKey());
        return p.getProfileImageUrl();
    }
}
