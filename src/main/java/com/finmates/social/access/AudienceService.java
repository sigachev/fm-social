package com.finmates.social.access;

import com.finmates.social.block.BlockRepository;
import com.finmates.social.follow.FollowRepository;
import com.finmates.social.post.Post;
import com.finmates.social.post.PostStatus;
import com.finmates.social.post.PostVisibility;
import com.finmates.social.profile.PermissionLevel;
import com.finmates.social.profile.PortfolioVisibility;
import com.finmates.social.profile.Profile;
import com.finmates.social.profile.ProfileRepository;
import com.finmates.social.profile.ProfileVisibility;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Who can see what. Every audience setting in Finmates is one of three levels:
 * <ul>
 *   <li><b>Everyone</b> ({@code PUBLIC}): anyone, signed in or not. On a private account (follows need approval)
 *       it means the owner's approved followers.</li>
 *   <li><b>Mates</b> ({@code MATES}): people the owner follows back. The owner chose each one; a one-click follow is
 *       not enough.</li>
 *   <li><b>Only me</b> ({@code PRIVATE}).</li>
 * </ul>
 * On top of that: a block in either direction hides everything, and content needs its account to be visible
 * (profile visibility) as well as its own level. The owner always sees their own content.
 *
 * <p>Every read of a post, a profile or portfolio numbers goes through {@link Viewer}. A Viewer caches its lookups,
 * so filtering a page of posts from many authors costs a few queries, not one per post. Make one per request.
 */
@Service
public class AudienceService {

    public enum Level { EVERYONE, MATES, ONLY_ME }

    private final FollowRepository follows;
    private final BlockRepository blocks;
    private final ProfileRepository profiles;

    public AudienceService(FollowRepository follows, BlockRepository blocks, ProfileRepository profiles) {
        this.follows = follows;
        this.blocks = blocks;
        this.profiles = profiles;
    }

    /** viewerId null = signed out. */
    public Viewer viewer(Long viewerId) {
        return new Viewer(viewerId);
    }

    public static Level of(PostVisibility v) {
        return v == null || v == PostVisibility.PUBLIC ? Level.EVERYONE : v == PostVisibility.MATES ? Level.MATES : Level.ONLY_ME;
    }

    public static Level of(ProfileVisibility v) {
        return v == null || v == ProfileVisibility.PUBLIC ? Level.EVERYONE : v == ProfileVisibility.MATES ? Level.MATES : Level.ONLY_ME;
    }

    public static Level of(PortfolioVisibility v) {
        return v == null || v == PortfolioVisibility.PUBLIC ? Level.EVERYONE : v == PortfolioVisibility.MATES ? Level.MATES : Level.ONLY_ME;
    }

    /** DISABLED = nobody (not even the owner can comment on their own notes through it; they own them anyway). */
    public static Level of(PermissionLevel v) {
        return v == null || v == PermissionLevel.PUBLIC ? Level.EVERYONE : v == PermissionLevel.MATES ? Level.MATES : Level.ONLY_ME;
    }

    /** One viewer's answers, cached for the life of a request. Not thread-safe; don't share across requests. Not final so tests can stub it. */
    public class Viewer {
        private final Long id;
        private Set<Long> following;
        private Set<Long> mates;
        private final Map<Long, Boolean> blocked = new HashMap<>();
        private final Map<Long, Optional<Profile>> profileCache = new HashMap<>();

        private Viewer(Long id) {
            this.id = id;
        }

        public Long id() {
            return id;
        }

        public boolean isOwner(Long ownerId) {
            return id != null && id.equals(ownerId);
        }

        public boolean isMate(Long ownerId) {
            if (id == null || ownerId == null || id.equals(ownerId)) return false;
            if (mates == null) mates = follows.findAllMateIds(id);
            return mates.contains(ownerId);
        }

        /** An ACTIVE follow (approved, on a private account). */
        public boolean follows(Long ownerId) {
            if (id == null || ownerId == null) return false;
            if (following == null) following = follows.findAllFollowingIds(id);
            return following.contains(ownerId);
        }

        public boolean blockedWith(Long ownerId) {
            if (id == null || ownerId == null || id.equals(ownerId)) return false;
            return blocked.computeIfAbsent(ownerId, o -> blocks.existsBlockInEitherDirection(id, o));
        }

        public Optional<Profile> profile(Long ownerId) {
            return profileCache.computeIfAbsent(ownerId, profiles::findById);
        }

        /** The level check alone, plus blocks; not the account check. */
        public boolean allows(Long ownerId, Level level) {
            if (ownerId == null) return false;
            if (isOwner(ownerId)) return true;
            if (blockedWith(ownerId)) return false;
            Profile p = profile(ownerId).orElse(null);
            if (p == null) return false;
            return switch (level) {
                case ONLY_ME -> false;
                case MATES -> isMate(ownerId);
                case EVERYONE -> !p.isPrivate() || follows(ownerId);
            };
        }

        /** The account itself (profile visibility, private account, blocks). */
        public boolean canSeeAccount(Long ownerId) {
            Profile p = profile(ownerId).orElse(null);
            return isOwner(ownerId) || (p != null && allows(ownerId, of(p.getProfileVisibility())));
        }

        /** Portfolio numbers: the account and the portfolio level. */
        public boolean canSeePortfolio(Long ownerId) {
            Profile p = profile(ownerId).orElse(null);
            return isOwner(ownerId) || (p != null && canSeeAccount(ownerId) && allows(ownerId, of(p.getPortfolioVisibility())));
        }

        /**
         * A post: removed posts are gone for everyone; hidden or under-review ones only for their author; otherwise the
         * author's account and the post's own audience.
         */
        public boolean canSee(Post post) {
            if (post == null || post.getStatus() == PostStatus.REMOVED) return false;
            Long author = post.getAuthorId();
            if (isOwner(author)) return true;
            if (post.getStatus() != PostStatus.ACTIVE) return false;
            return canSeeAccount(author) && allows(author, of(post.getVisibility()));
        }
    }
}
