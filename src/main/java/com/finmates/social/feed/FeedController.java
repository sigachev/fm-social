package com.finmates.social.feed;

import com.finmates.social.block.BlockRepository;
import com.finmates.social.common.security.AuthenticatedUser;
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

    @GetMapping
    @Operation(summary = "Get personalized feed",
               description = "Returns posts from followed users, newest first. Use nextCursor for pagination.")
    public ResponseEntity<FeedResponse> getFeed(
            @RequestParam(defaultValue = "9223372036854775807") long cursor,
            @RequestParam(defaultValue = "20") int limit) {

        limit = Math.min(limit, MAX_LIMIT);
        if (limit <= 0) limit = DEFAULT_LIMIT;

        Long userId = authenticatedUser.currentUserId();

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

        for (Long postId : postIds) {
            Post post = postsById.get(postId);
            if (post == null) continue;  // deleted post, skip
            if (post.getStatus() == com.finmates.social.post.PostStatus.REMOVED) continue;
            // Filter posts from users who blocked us or we blocked
            if (blockRepository.existsBlockInEitherDirection(userId, post.getAuthorId())) continue;

            responses.add(postService.toPublicResponse(post));

            // Track oldest timestamp for nextCursor
            Long ts = feedService.getPostTimestamp(userId, postId);
            if (ts != null && (oldestTimestamp == null || ts < oldestTimestamp)) {
                oldestTimestamp = ts;
            }
        }

        boolean hasMore = postIds.size() == limit;
        return ResponseEntity.ok(new FeedResponse(responses, oldestTimestamp, hasMore));
    }
}
