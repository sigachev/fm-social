package com.finmates.social.profile;

import com.finmates.social.common.exception.ForbiddenActionException;
import com.finmates.social.common.exception.ResourceNotFoundException;
import com.finmates.social.follow.FollowRepository;
import com.finmates.social.profile.dto.ProfilePublicResponse;
import com.finmates.social.profile.dto.ProfileResponse;
import com.finmates.social.profile.dto.ProfileUpdateRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional(readOnly = true)
public class ProfileService {

    private final ProfileRepository profileRepository;
    private final FollowRepository followRepository;

    public ProfileService(ProfileRepository profileRepository, FollowRepository followRepository) {
        this.profileRepository = profileRepository;
        this.followRepository = followRepository;
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
                boolean isFollowing = viewerId != null &&
                        followRepository.existsByFollowerIdAndFollowedId(viewerId, targetUserId);
                yield toFilteredPublicResponse(profile, isFollowing, isFollowing);
            }
            case PUBLIC -> toFilteredPublicResponse(profile, true, true);
        };
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private void applyUpdates(Profile profile, ProfileUpdateRequest req) {
        if (req.getBio() != null)                     profile.setBio(req.getBio());
        if (req.getDisplayName() != null)             profile.setDisplayName(req.getDisplayName());
        if (req.getFirstName() != null)               profile.setFirstName(req.getFirstName());
        if (req.getLastName() != null)                profile.setLastName(req.getLastName());
        if (req.getNameDisplayPreference() != null)   profile.setNameDisplayPreference(req.getNameDisplayPreference());
        if (req.getProfileImageKey() != null)         profile.setProfileImageKey(req.getProfileImageKey());
        if (req.getCoverImageKey() != null)           profile.setCoverImageKey(req.getCoverImageKey());
        if (req.getThumbnailKey() != null)            profile.setThumbnailKey(req.getThumbnailKey());
        if (req.getProfileImageUrl() != null)         profile.setProfileImageUrl(req.getProfileImageUrl());
        if (req.getThumbnailUrl() != null)            profile.setThumbnailUrl(req.getThumbnailUrl());
        if (req.getLocation() != null)                profile.setLocation(req.getLocation());
        if (req.getWebsite() != null)                 profile.setWebsite(req.getWebsite());
        if (req.getTimezone() != null)                profile.setTimezone(req.getTimezone());
        if (req.getTwitterHandle() != null)           profile.setTwitterHandle(req.getTwitterHandle());
        if (req.getDiscordHandle() != null)           profile.setDiscordHandle(req.getDiscordHandle());
        if (req.getTelegramHandle() != null)          profile.setTelegramHandle(req.getTelegramHandle());
        if (req.getInstagramHandle() != null)         profile.setInstagramHandle(req.getInstagramHandle());
        if (req.getFacebookHandle() != null)          profile.setFacebookHandle(req.getFacebookHandle());
        if (req.getLinkedinHandle() != null)          profile.setLinkedinHandle(req.getLinkedinHandle());
        if (req.getWhatsappHandle() != null)          profile.setWhatsappHandle(req.getWhatsappHandle());
        if (req.getProfileVisibility() != null)       profile.setProfileVisibility(req.getProfileVisibility());
        if (req.getPortfolioVisibility() != null)     profile.setPortfolioVisibility(req.getPortfolioVisibility());
        if (req.getShowPnl() != null)                 profile.setShowPnl(req.getShowPnl());
        if (req.getShowPositions() != null)           profile.setShowPositions(req.getShowPositions());
        if (req.getShowLocation() != null)            profile.setShowLocation(req.getShowLocation());
        if (req.getShowRealName() != null)            profile.setShowRealName(req.getShowRealName());
        if (req.getShowTradingActivity() != null)     profile.setShowTradingActivity(req.getShowTradingActivity());
        if (req.getShowTradeHistory() != null)        profile.setShowTradeHistory(req.getShowTradeHistory());
        if (req.getShowOnlineStatus() != null)        profile.setShowOnlineStatus(req.getShowOnlineStatus());
        if (req.getShowInLeaderboard() != null)       profile.setShowInLeaderboard(req.getShowInLeaderboard());
        if (req.getAllowFollowers() != null)           profile.setAllowFollowers(req.getAllowFollowers());
        if (req.getAllowCopyTrading() != null)         profile.setAllowCopyTrading(req.getAllowCopyTrading());
        if (req.getNotesPermission() != null)         profile.setNotesPermission(req.getNotesPermission());
        if (req.getSignalCommentsPermission() != null) profile.setSignalCommentsPermission(req.getSignalCommentsPermission());
        if (req.getAllowMessages() != null)            profile.setAllowMessages(req.getAllowMessages());
    }

    public ProfileResponse toFullResponse(Profile p) {
        return new ProfileResponse(
                p.getUserId(), p.getBio(), p.getDisplayName(), p.getFirstName(), p.getLastName(),
                p.getNameDisplayPreference(), p.getProfileImageKey(), p.getCoverImageKey(),
                p.getThumbnailKey(), p.getProfileImageUrl(), p.getThumbnailUrl(),
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
     * @param showAllFields if true the viewer has follower-level access (can see content gated on FOLLOWERS)
     * @param showSocialHandles if true social handles are included
     */
    private ProfilePublicResponse toFilteredPublicResponse(Profile p, boolean showAllFields, boolean showSocialHandles) {
        return new ProfilePublicResponse(
                p.getUserId(),
                p.getDisplayName(),
                // realName: only if showAllFields AND showRealName is enabled
                showAllFields && p.isShowRealName() ? p.getFirstName() : null,
                showAllFields && p.isShowRealName() ? p.getLastName() : null,
                p.getProfileImageKey(),
                p.getProfileImageUrl(),
                p.getThumbnailKey(),
                p.getThumbnailUrl(),
                showAllFields ? p.getCoverImageKey() : null,
                showAllFields ? p.getBio() : null,
                // location: show if showAllFields AND showLocation is enabled
                showAllFields && p.isShowLocation() ? p.getLocation() : null,
                showAllFields ? p.getWebsite() : null,
                showAllFields ? p.getTimezone() : null,
                // social handles: only when viewer has follower access
                showSocialHandles ? p.getTwitterHandle() : null,
                showSocialHandles ? p.getDiscordHandle() : null,
                showSocialHandles ? p.getTelegramHandle() : null,
                showSocialHandles ? p.getInstagramHandle() : null,
                showSocialHandles ? p.getFacebookHandle() : null,
                showSocialHandles ? p.getLinkedinHandle() : null,
                showSocialHandles ? p.getWhatsappHandle() : null,
                p.getProfileVisibility(),
                p.getPortfolioVisibility(),
                p.isShowPnl(),
                p.isShowPositions(),
                p.isShowTradingActivity(),
                p.isShowTradeHistory(),
                p.isShowOnlineStatus(),
                p.isShowInLeaderboard(),
                p.getCreatedAt()
        );
    }
}
