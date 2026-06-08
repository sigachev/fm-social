package com.finmates.social.feed;

import com.finmates.social.follow.FollowRepository;
import com.finmates.social.post.Post;
import com.finmates.social.post.PostRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Manages per-user feed sorted sets in Redis.
 *
 * Feed key format: {@code feed:user:{userId}}
 * Score: post createdAt epoch milliseconds (for chronological ordering).
 * Value: post ID as string.
 *
 * Fan-out is synchronous for now; flagged for async queue in a future prompt.
 * When {@code fm-social.redis.use-redis=false}, Redis operations are no-ops.
 */
@Service
@Slf4j
public class FeedService {

    @Autowired
    private FollowRepository followRepository;
    @Autowired
    private PostRepository postRepository;
    @Autowired(required = false)
    private RedisTemplate<String, String> redisTemplate;

    @Value("${finmates.feed.max-entries-per-user:1000}")
    private int maxEntriesPerUser;

    @Value("${finmates.feed.backfill-on-activate-count:50}")
    private int backfillOnActivateCount;

    private static String feedKey(Long userId) {
        return "feed:user:" + userId;
    }

    /**
     * Fan-out: add postId to each follower's feed + the author's own feed.
     * Called after post save. Failures are swallowed — the post is already persisted.
     * TODO Prompt 6/7: move to async queue for authors with > fanout-cold-threshold followers.
     */
    public void fanOutPost(Post post) {
        try {
            List<Long> followerIds = followRepository.findAllFollowerIds(post.getAuthorId());
            double score = post.getCreatedAt() != null
                    ? post.getCreatedAt().toInstant().toEpochMilli()
                    : System.currentTimeMillis();
            String value = String.valueOf(post.getId());

            // Push to each follower's feed
            for (Long followerId : followerIds) {
                pushToFeed(feedKey(followerId), value, score);
            }

            // Push to author's own feed (see own posts)
            pushToFeed(feedKey(post.getAuthorId()), value, score);

            log.debug("Fanned out post {} to {} follower feeds", post.getId(), followerIds.size());
        } catch (Exception e) {
            log.error("Feed fan-out failed for post {} — feed will be stale until next read: {}",
                    post.getId(), e.getMessage());
        }
    }

    private void pushToFeed(String key, String value, double score) {
        if (redisTemplate == null) {
            log.debug("Redis disabled, skipping feed push for key={}", key);
            return;
        }
        try {
            redisTemplate.opsForZSet().add(key, value, score);
            // Trim to max entries (keep newest: remove rank 0 to -(maxEntriesPerUser+1))
            long size = Long.MAX_VALUE;
            Long current = redisTemplate.opsForZSet().size(key);
            if (current != null) size = current;
            if (size > maxEntriesPerUser) {
                redisTemplate.opsForZSet().removeRange(key, 0, size - maxEntriesPerUser - 1);
            }
        } catch (Exception e) {
            log.warn("Failed to push to feed key {}: {}", key, e.getMessage());
        }
    }

    /**
     * Returns post IDs from the user's feed, ordered newest-first.
     *
     * @param userId          feed owner
     * @param cursorTimestamp return posts older than this timestamp (exclusive). Use Long.MAX_VALUE for first page.
     * @param limit           max results (caller enforces ≤ 50)
     * @return list of post IDs, newest first
     */
    public List<Long> getFeed(Long userId, long cursorTimestamp, int limit) {
        if (redisTemplate == null) {
            log.debug("Redis disabled, returning empty feed for user={}", userId);
            return Collections.emptyList();
        }
        try {
            double maxScore = cursorTimestamp == Long.MAX_VALUE ? Double.MAX_VALUE : (double)(cursorTimestamp - 1);
            Set<String> entries = redisTemplate.opsForZSet()
                    .reverseRangeByScore(feedKey(userId), Double.NEGATIVE_INFINITY, maxScore,
                            0, limit);
            if (entries == null || entries.isEmpty()) return Collections.emptyList();
            return entries.stream()
                    .map(Long::valueOf)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Feed read failed for user {}: {}", userId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Backfill the follower's feed with the followee's recent posts when a follow becomes ACTIVE.
     *
     * <p>Called from {@link com.finmates.social.follow.FollowService} after either a fresh
     * follow against a public profile, an explicit accept of a pending request, or a
     * private→public privacy flip's auto-accept loop. The number of posts is bounded by
     * {@code finmates.feed.backfill-on-activate-count} (default 50).</p>
     *
     * <p>Best-effort: any Redis or DB failure is logged at WARN and swallowed. The follow
     * row has already been committed by the caller's transaction; a stale feed will catch up
     * on the next post fan-out.</p>
     *
     * @param followerId the user whose feed to extend
     * @param followeeId the user whose recent posts to fan in
     */
    public void onFollowActivated(Long followerId, Long followeeId) {
        try {
            Pageable limit = PageRequest.of(0, backfillOnActivateCount);
            List<Post> recent = postRepository.findActiveByAuthorIdDesc(followeeId, limit);
            if (recent.isEmpty()) {
                return;
            }
            String key = feedKey(followerId);
            for (Post post : recent) {
                double score = post.getCreatedAt() != null
                        ? post.getCreatedAt().toInstant().toEpochMilli()
                        : System.currentTimeMillis();
                pushToFeed(key, String.valueOf(post.getId()), score);
            }
            log.debug("Backfilled {} posts from user {} into user {}'s feed",
                    recent.size(), followeeId, followerId);
        } catch (Exception e) {
            log.warn("Feed backfill failed for follower={} followee={}: {}",
                    followerId, followeeId, e.getMessage());
        }
    }

    /**
     * Gets the score (timestamp) for a post in the user's feed.
     * Used to compute the next cursor.
     */
    public Long getPostTimestamp(Long userId, Long postId) {
        if (redisTemplate == null) {
            return null;
        }
        try {
            Double score = redisTemplate.opsForZSet().score(feedKey(userId), String.valueOf(postId));
            return score != null ? score.longValue() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
