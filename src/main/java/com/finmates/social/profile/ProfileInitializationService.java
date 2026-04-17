package com.finmates.social.profile;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Ensures a {@code social.profiles} row exists for any authenticated user.
 *
 * <p>Uses an in-process Caffeine cache keyed by userId to avoid hitting the DB on
 * every request. Once a profile is confirmed to exist (or was just created), the
 * userId is cached for 60 seconds — subsequent requests within the window skip
 * both the SELECT and any potential INSERT entirely.
 *
 * <p>Thread-safe: {@link DataIntegrityViolationException} from a concurrent INSERT
 * is swallowed silently (race-condition safety).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProfileInitializationService {

    private final ProfileRepository profileRepository;

    /** userId → confirmed-exists flag, 60-second TTL */
    private final Cache<Long, Boolean> profileExistsCache = Caffeine.newBuilder()
            .expireAfterWrite(60, TimeUnit.SECONDS)
            .maximumSize(50_000)
            .build();

    /**
     * Ensures a profile row exists for {@code userId}. No-op if already cached.
     *
     * @param userId      the user's numeric ID (from JWT {@code user_id} claim)
     * @param displayName initial display name (from JWT {@code name} or {@code preferred_username});
     *                    falls back to {@code "User " + userId} if null/blank
     */
    public void ensureProfileExists(Long userId, String displayName) {
        if (Boolean.TRUE.equals(profileExistsCache.getIfPresent(userId))) {
            return;
        }

        if (profileRepository.existsById(userId)) {
            profileExistsCache.put(userId, Boolean.TRUE);
            return;
        }

        createDefaultProfile(userId, displayName);
    }

    private void createDefaultProfile(Long userId, String displayName) {
        String resolvedName = (displayName != null && !displayName.isBlank())
                ? displayName.trim()
                : "User " + userId;

        try {
            Profile profile = new Profile();
            profile.setUserId(userId);
            profile.setDisplayName(resolvedName);
            // bio, avatarKey, coverKey remain null — schema defaults apply
            // profileVisibility, portfolioVisibility default to PUBLIC via @PrePersist
            // all boolean flags default to their schema defaults via @PrePersist

            profileRepository.save(profile);
            profileExistsCache.put(userId, Boolean.TRUE);
            log.info("ProfileInitializationService: auto-created profile for userId={} displayName='{}'",
                    userId, resolvedName);

        } catch (DataIntegrityViolationException e) {
            // Another concurrent request beat us to the INSERT — profile now exists
            profileExistsCache.put(userId, Boolean.TRUE);
            log.debug("ProfileInitializationService: profile for userId={} already exists (race — ignored)", userId);
        }
    }
}
