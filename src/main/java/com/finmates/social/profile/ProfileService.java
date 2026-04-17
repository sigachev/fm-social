package com.finmates.social.profile;

import com.finmates.social.common.exception.ForbiddenActionException;
import com.finmates.social.common.exception.ResourceNotFoundException;
import com.finmates.social.follow.FollowRepository;
import com.finmates.social.profile.dto.ProfilePublicResponse;
import com.finmates.social.profile.dto.ProfileResponse;
import com.finmates.social.profile.dto.ProfileUpdateRequest;
import com.finmates.social.upload.S3Service;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
@Transactional(readOnly = true)
public class ProfileService {

    private final ProfileRepository profileRepository;
    private final FollowRepository followRepository;
    private final S3Service s3Service;

    public ProfileService(ProfileRepository profileRepository,
                          FollowRepository followRepository,
                          S3Service s3Service) {
        this.profileRepository = profileRepository;
        this.followRepository = followRepository;
        this.s3Service = s3Service;
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
                boolean isFollowing = viewerId != null &&
                        followRepository.existsByFollowerIdAndFollowedId(viewerId, targetUserId);
                yield toFilteredPublicResponse(profile, isFollowing, isFollowing);
            }
            case PUBLIC -> toFilteredPublicResponse(profile, true, true);
        };
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private void applyUpdates(Profile profile, ProfileUpdateRequest req) {
        // Note: avatarKey and coverKey are handled by S3 promotion above — not set here.
        if (req.getBio() != null)                      profile.setBio(req.getBio());
        if (req.getDisplayName() != null)              profile.setDisplayName(req.getDisplayName());
        if (req.getFirstName() != null)                profile.setFirstName(req.getFirstName());
        if (req.getLastName() != null)                 profile.setLastName(req.getLastName());
        if (req.getNameDisplayPreference() != null)    profile.setNameDisplayPreference(req.getNameDisplayPreference());
        // thumbnailKey: stored directly — S3 promotion deferred to Prompt 5 (same pattern as avatarKey)
        if (req.getThumbnailKey() != null)             profile.setThumbnailKey(req.getThumbnailKey());
        // OAuth avatar URL: mutually exclusive with avatarKey (DB constraint avatar_key_exclusive)
        if (req.getProfileImageUrl() != null) {
            profile.setProfileImageUrl(req.getProfileImageUrl());
            profile.setAvatarKey(null); // enforce mutual exclusion
        }
        // OAuth thumbnail URL: mutually exclusive with thumbnailKey (DB constraint thumbnail_exclusive)
        if (req.getThumbnailUrl() != null) {
            profile.setThumbnailUrl(req.getThumbnailUrl());
            profile.setThumbnailKey(null); // enforce mutual exclusion
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
        String avatarUrl   = resolveAvatarUrl(p);
        String coverUrl    = p.getCoverKey()    != null ? s3Service.createPresignedGet(p.getCoverKey())    : null;
        String thumbnailUrl = resolveThumbnailUrl(p);

        return new ProfileResponse(
                p.getUserId(), p.getBio(), p.getDisplayName(), p.getFirstName(), p.getLastName(),
                p.getNameDisplayPreference(),
                avatarUrl, coverUrl, thumbnailUrl,
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
        String avatarUrl    = resolveAvatarUrl(p);
        String thumbnailUrl = resolveThumbnailUrl(p);
        String coverUrl     = showAllFields && p.getCoverKey() != null
                ? s3Service.createPresignedGet(p.getCoverKey()) : null;

        return new ProfilePublicResponse(
                p.getUserId(),
                p.getDisplayName(),
                showAllFields && p.isShowRealName() ? p.getFirstName() : null,
                showAllFields && p.isShowRealName() ? p.getLastName()  : null,
                avatarUrl,
                thumbnailUrl,
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

    /** S3 presigned URL if avatarKey is set; external OAuth URL otherwise; null if neither. */
    private String resolveAvatarUrl(Profile p) {
        if (p.getAvatarKey() != null) return s3Service.createPresignedGet(p.getAvatarKey());
        return p.getProfileImageUrl();
    }

    /** S3 presigned URL if thumbnailKey is set; external OAuth thumbnail URL otherwise. */
    private String resolveThumbnailUrl(Profile p) {
        if (p.getThumbnailKey() != null) return s3Service.createPresignedGet(p.getThumbnailKey());
        return p.getThumbnailUrl();
    }
}
