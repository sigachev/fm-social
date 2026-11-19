package com.finmates.social.feed;

import com.finmates.social.block.BlockRepository;
import com.finmates.social.common.security.AuthenticatedUser;
import com.finmates.social.feed.dto.FollowingActivityEvent;
import com.finmates.social.post.Post;
import com.finmates.social.post.PostRepository;
import com.finmates.social.post.PostService;
import com.finmates.social.post.dto.PostResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/feed")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Feed", description = "Personalized post feed")
public class FeedController {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final FeedService feedService;
    private final PostRepository postRepository;
    private final PostService postService;
    private final BlockRepository blockRepository;
    private final AuthenticatedUser authenticatedUser;
    private final FollowingFeedService followingFeedService;
    private final com.finmates.social.follow.FollowRepository followRepository;
    private final com.finmates.social.access.AudienceService audience;

    /**
     * GET /api/feed/everyone: public posts from everyone (private accounts and blocks left out),
     * newest first. {@code cursor} is the last post id of the previous page.
     */
    @GetMapping("/everyone")
    @Operation(summary = "Public posts from everyone, newest first")
    public ResponseEntity<FeedResponse> getEveryone(
            @RequestParam(defaultValue = "9223372036854775807") long cursor,
            @RequestParam(defaultValue = "20") int limit) {
        limit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        Long userId = authenticatedUser.currentUserId();
        List<Post> page = postRepository.findPublicBeforeCursor(cursor, org.springframework.data.domain.PageRequest.of(0, limit));
        List<PostResponse> out = new ArrayList<>();
        var viewer = audience.viewer(userId);
        for (Post p : page) {
            if (!viewer.canSee(p)) continue;   // blocks (the query already keeps only public posts on public accounts)
            out.add(postService.toPublicResponse(p));
        }
        Long next = page.isEmpty() ? null : page.get(page.size() - 1).getId();
        return ResponseEntity.ok(new FeedResponse(out, next, page.size() == limit));
    }

    @GetMapping
    @Operation(summary = "Get personalized feed",
               description = "Returns posts from followed users, newest first. Use nextCursor for pagination.")
    public ResponseEntity<FeedResponse> getFeed(
            @RequestParam(defaultValue = "9223372036854775807") long cursor,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "false") boolean matesOnly) {

        limit = Math.min(limit, MAX_LIMIT);
        if (limit <= 0) limit = DEFAULT_LIMIT;

        Long userId = authenticatedUser.currentUserId();

        // Mates only: people who follow you back.
        java.util.Set<Long> mates = matesOnly ? followRepository.findAllMateIds(userId) : null;

        // 1. Get post IDs from Redis feed
        List<Long> postIds = feedService.getFeed(userId, cursor, limit);

        if (postIds.isEmpty()) {
            return ResponseEntity.ok(new FeedResponse(List.of(), null, false));
        }

        // 2. Fetch posts from DB
        Map<Long, Post> postsById = postRepository.findAllById(postIds).stream()
                .collect(Collectors.toMap(Post::getId, p -> p));

        // 3. Build responses in feed order (maintain Redis ordering), filter blocked users
        List<PostResponse> responses = new ArrayList<>();
        Long oldestTimestamp = null;

        var viewer = audience.viewer(userId);
        for (Long postId : postIds) {
            // The cursor moves past every entry, shown or not, so a filtered post can't stall paging.
            Long ts = feedService.getPostTimestamp(userId, postId);
            if (ts != null && (oldestTimestamp == null || ts < oldestTimestamp)) {
                oldestTimestamp = ts;
            }
            Post post = postsById.get(postId);
            if (post == null) continue;  // deleted post, skip
            // Removed, blocked, or outside the post's audience now (people unfollow; posts go mates-only).
            if (!viewer.canSee(post)) continue;
            if (mates != null && !mates.contains(post.getAuthorId())) continue;

            responses.add(postService.toPublicResponse(post));
        }

        boolean hasMore = postIds.size() == limit;
        return ResponseEntity.ok(new FeedResponse(responses, oldestTimestamp, hasMore));
    }

    /**
     * GET /api/feed/following — trade-event activity feed for users the viewer follows.
     *
     * <p>Returns a chronologically-ordered list of {@link FollowingActivityEvent} rows
     * spanning POSITION_OPENED and POSITION_CLOSED actions across the viewer's followed
     * traders, each enriched with rolling-returns trader-performance metadata. Backed
     * by {@link FollowingFeedService} — see that class's javadoc for the full pipeline
     * (follow-graph → trades fetch → expand → sort → batch hydrate).
     *
     * <p>Pagination is intentionally simpler than {@code /api/feed} (no cursor): rolling
     * activity changes too quickly for cursor-based pagination to feel right, and the
     * widget on the dashboard renders a fixed-size page anyway. {@code limit} is
     * server-clamped to {@code [1, FollowingFeedService.MAX_LIMIT]}.
     */
    @GetMapping("/following")
    @Operation(summary = "Get following activity feed",
               description = "Returns POSITION_OPENED / POSITION_CLOSED events for users the viewer follows, with trader-performance metadata.")
    public ResponseEntity<List<FollowingActivityEvent>> getFollowingActivity(
            @RequestParam(defaultValue = "20") int limit) {
        Long userId = authenticatedUser.currentUserId();
        return ResponseEntity.ok(followingFeedService.getFollowingActivity(userId, limit));
    }
}
