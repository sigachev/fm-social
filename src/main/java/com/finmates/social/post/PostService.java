package com.finmates.social.post;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finmates.social.block.BlockRepository;
import com.finmates.social.common.PageResponse;
import com.finmates.social.common.exception.EditWindowExpiredException;
import com.finmates.social.common.exception.ForbiddenActionException;
import com.finmates.social.common.exception.ResourceNotFoundException;
import com.finmates.social.edit.PostEdit;
import com.finmates.social.edit.PostEditRepository;
import com.finmates.social.feed.FeedResponse;
import com.finmates.social.feed.FeedService;
import com.finmates.social.follow.FollowRepository;
import com.finmates.social.post.dto.PostCreateRequest;
import com.finmates.social.post.dto.PostResponse;
import com.finmates.social.post.dto.PostUpdateRequest;
import com.finmates.social.profile.ProfileRepository;
import com.finmates.social.upload.S3Service;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@Transactional(readOnly = true)
public class PostService {

    private final PostRepository postRepository;
    private final PostEditRepository postEditRepository;
    private final S3Service s3Service;
    private final FeedService feedService;
    private final ProfileRepository profileRepository;
    private final FollowRepository followRepository;
    private final BlockRepository blockRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    /** Short-lived cache: avoids a profile DB hit per post during feed / profile renders. */
    private final Cache<Long, AuthorInfo> authorCache = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .maximumSize(1_000)
            .build();

    private record AuthorInfo(String displayName, String avatarUrl) {}

    @Value("${finmates.edit-window-minutes}")
    private int editWindowMinutes;

    public PostService(PostRepository postRepository,
                       PostEditRepository postEditRepository,
                       S3Service s3Service,
                       FeedService feedService,
                       ProfileRepository profileRepository,
                       FollowRepository followRepository,
                       BlockRepository blockRepository,
                       RedisTemplate<String, String> redisTemplate,
                       ObjectMapper objectMapper) {
        this.postRepository = postRepository;
        this.postEditRepository = postEditRepository;
        this.s3Service = s3Service;
        this.feedService = feedService;
        this.profileRepository = profileRepository;
        this.followRepository = followRepository;
        this.blockRepository = blockRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PostResponse createPost(Long authorId, String authorUsername, PostCreateRequest req) {
        List<String> pendingKeys = req.getMediaKeys() != null ? req.getMediaKeys() : List.of();

        // Step 1 & 2: Validate ownership and existence before touching the DB
        for (String key : pendingKeys) {
            s3Service.validateOwnership(authorId, key, "posts");
            if (!s3Service.objectExists(key)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Media not yet uploaded to S3: " + key);
            }
        }

        // Step 3: Save post first — we need the postId for the final S3 key path
        Post post = new Post();
        post.setAuthorId(authorId);
        post.setAuthorUsername(authorUsername);
        post.setContent(req.getContent());
        post.setMediaKeys(new ArrayList<>());
        post.setVisibility(req.getVisibility() != null ? req.getVisibility() : PostVisibility.PUBLIC);
        post = postRepository.save(post);

        // Steps 4–9: Promote pending keys to final paths
        if (!pendingKeys.isEmpty()) {
            List<String> finalKeys = new ArrayList<>();
            List<String> successfullyCopied = new ArrayList<>();

            for (String pendingKey : pendingKeys) {
                try {
                    String filename = pendingKey.substring(pendingKey.lastIndexOf('/') + 1);
                    String finalKey = "posts/" + post.getId() + "/" + filename;
                    s3Service.copy(pendingKey, finalKey);
                    finalKeys.add(finalKey);
                    successfullyCopied.add(pendingKey);
                } catch (Exception e) {
                    log.error("Failed to promote S3 key {} for post {}: {}",
                            pendingKey, post.getId(), e.getMessage());
                }
            }

            post.setMediaKeys(finalKeys);
            post = postRepository.save(post);

            for (String pendingKey : successfullyCopied) {
                s3Service.delete(pendingKey);
            }
        }

        // Fan-out to follower feeds (best-effort — post is already saved)
        try {
            feedService.fanOutPost(post);
        } catch (Exception e) {
            log.error("Feed fan-out failed for post {}: {}", post.getId(), e.getMessage());
        }

        return toResponse(post);
    }

    public PostResponse getPost(Long id) {
        return toResponse(findActivePost(id));
    }

    /** Admin preview — returns post regardless of status (including REMOVED). */
    public PostResponse getPostForAdmin(Long id) {
        return toResponse(findPost(id));
    }

    @Transactional
    public PostResponse updatePost(Long id, Long currentUserId, PostUpdateRequest req) {
        Post post = findPost(id);
        checkOwnership(post.getAuthorId(), currentUserId, "edit", "post");
        checkEditWindow(post.getCreatedAt(), "Posts");

        PostEdit edit = new PostEdit();
        edit.setPostId(post.getId());
        edit.setPreviousContent(post.getContent());
        postEditRepository.save(edit);

        post.setContent(req.getContent());
        post.setEditCount(post.getEditCount() + 1);
        post.setLastEditedAt(OffsetDateTime.now());
        return toResponse(postRepository.save(post));
    }

    @Transactional
    public void deletePost(Long id, Long currentUserId) {
        Post post = findPost(id);
        checkOwnership(post.getAuthorId(), currentUserId, "delete", "post");
        post.setStatus(PostStatus.REMOVED);
        postRepository.save(post);
    }

    /** Admin moderation: removes a post with an audit trail (who removed it, why). */
    @Transactional
    public PostResponse adminRemovePost(Long id, Long adminUserId, String reason) {
        Post post = findPost(id);
        post.setStatus(PostStatus.REMOVED);
        post.setRemovedAt(OffsetDateTime.now());
        post.setRemovedBy(adminUserId);
        post.setRemovalReason(reason);
        return toResponse(postRepository.save(post));
    }

    /** Admin moderation: restores a removed post and clears the removal audit fields. */
    @Transactional
    public PostResponse adminRestorePost(Long id, Long adminUserId) {
        Post post = findPost(id);
        if (post.getStatus() != PostStatus.REMOVED) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "Post is not removed");
        }
        post.setStatus(PostStatus.ACTIVE);
        post.setRemovedAt(null);
        post.setRemovedBy(null);
        post.setRemovalReason(null);
        return toResponse(postRepository.save(post));
    }

    /** Legacy page-based query — kept for internal use; prefer getUserPostsFeed for API responses. */
    public PageResponse<PostResponse> getPostsByUser(Long authorId, Pageable pageable) {
        return PageResponse.from(postRepository.findActiveByAuthorId(authorId, pageable).map(this::toResponse));
    }

    /** Returns user posts in FeedResponse shape (cursor-based) — used by the public endpoint. */
    public FeedResponse getUserPostsFeed(Long userId, Long cursor, int limit) {
        int fetchSize = limit + 1;
        Pageable pageable = PageRequest.of(0, fetchSize);

        List<Post> posts = cursor == null
                ? postRepository.findActiveByAuthorIdDesc(userId, pageable)
                : postRepository.findActiveByAuthorIdBeforeCursor(userId, cursor, pageable);

        boolean hasMore = posts.size() > limit;
        if (hasMore) posts = new ArrayList<>(posts.subList(0, limit));

        Long nextCursor = (hasMore && !posts.isEmpty()) ? posts.get(posts.size() - 1).getId() : null;
        List<PostResponse> responses = posts.stream().map(this::toResponse).toList();

        return new FeedResponse(responses, nextCursor, hasMore);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private Post findPost(Long id) {
        return postRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Post not found: " + id));
    }

    private Post findActivePost(Long id) {
        Post post = findPost(id);
        if (post.getStatus() == PostStatus.REMOVED) {
            throw new ResourceNotFoundException("Post not found: " + id);
        }
        return post;
    }

    private void checkOwnership(Long ownerId, Long requesterId, String action, String resource) {
        if (!ownerId.equals(requesterId)) {
            throw new ForbiddenActionException("Not authorized to " + action + " this " + resource);
        }
    }

    private void checkEditWindow(OffsetDateTime createdAt, String resourceName) {
        if (createdAt.plusMinutes(editWindowMinutes).isBefore(OffsetDateTime.now())) {
            throw new EditWindowExpiredException(
                    resourceName + " can only be edited within " + editWindowMinutes + " minutes of creation");
        }
    }

    /**
     * Resolves author display info (displayName + avatarUrl) via a short-lived Caffeine cache.
     * Falls back to (null, null) if no profile row exists yet.
     */
    private AuthorInfo resolveAuthor(Long authorId) {
        return authorCache.get(authorId, id ->
                profileRepository.findById(id)
                        .map(p -> {
                            String avatarUrl = p.getAvatarKey() != null
                                    ? s3Service.createPresignedGet(p.getAvatarKey())
                                    : p.getProfileImageUrl();   // OAuth avatar URL or null
                            return new AuthorInfo(p.getDisplayName(), avatarUrl);
                        })
                        .orElse(new AuthorInfo(null, null))
        );
    }

    /**
     * Public alias used by FeedController to convert a Post fetched outside this service.
     */
    public PostResponse toPublicResponse(Post post) {
        return toResponse(post);
    }

    /**
     * Maps a Post entity to a PostResponse, generating fresh presigned GET URLs for media keys
     * and resolving author display info from the profile cache.
     */
    public PostResponse toResponse(Post post) {
        List<String> mediaUrls = post.getMediaKeys().stream()
                .map(s3Service::createPresignedGet)
                .toList();

        AuthorInfo author = resolveAuthor(post.getAuthorId());

        return new PostResponse(
                post.getId(),
                post.getAuthorId(),
                post.getAuthorUsername(),
                author.displayName(),
                author.avatarUrl(),
                post.getContent(),
                mediaUrls,
                post.getStatus(),
                post.getVisibility(),
                post.getCommentCount(),
                post.getReactionCount(),
                post.getEditCount(),
                post.getLastEditedAt(),
                post.getCreatedAt(),
                post.getUpdatedAt(),
                post.getRemovedAt(),
                post.getRemovalReason(),
                post.getRemovedBy()
        );
    }

    // ── Cashtag feed (Phase 1 — token-detail social column) ─────────────────

    /** Scope discriminator for {@link #getPostsByCashtag}. */
    public enum CashtagScope { NETWORK, GLOBAL }

    /**
     * Redis key prefix for the {@code scope=GLOBAL} cache. TTL 2 minutes via
     * {@link #setIfAbsent} below. NETWORK scope is per-viewer and not cached.
     *
     * <p>Cache TTL 2min. New posts surface after at most 2 minutes. No best-effort
     * invalidation on write — the cost of cashtag-parsing every PostService.createPost
     * to invalidate matching keys is not justified by a 2-minute staleness window.
     */
    private static final String CASHTAG_CACHE_KEY_PREFIX = "posts:by-cashtag:global:";
    private static final Duration CASHTAG_CACHE_TTL = Duration.ofMinutes(2);

    /**
     * Recent posts tagged with a cashtag for the token-detail feed widget.
     *
     * <p>Scope:
     * <ul>
     *   <li>{@code NETWORK}: posts authored by users the viewer follows.
     *       Caller must pass non-null {@code viewerId}; controller enforces auth.</li>
     *   <li>{@code GLOBAL}: trending platform-wide. {@code viewerId} ignored
     *       (no block filter applied — there is no viewer context to block from).
     *       Response cached in Redis per (symbol, limit) for 2 minutes.</li>
     * </ul>
     *
     * <p>Cashtag matching uses {@code LOWER(content) LIKE '%$<symbol>%'} against
     * the un-indexed {@code posts.content} column. A {@code post_cashtags} join
     * table or tsvector index is tracked as follow-up work in CLAUDE.md — this
     * implementation is the agreed-upon scale-bounded mitigation.
     *
     * @param symbol asset symbol (e.g. "BTC"); normalized to uppercase
     * @param scope  NETWORK or GLOBAL
     * @param viewerId viewer user_id (required for NETWORK, ignored for GLOBAL)
     * @param limit  result cap; controller clamps to [1, 20]
     */
    public List<PostResponse> getPostsByCashtag(String symbol,
                                                CashtagScope scope,
                                                Long viewerId,
                                                int limit) {
        final String normalized = symbol.trim().toUpperCase();
        // Pattern is lowercased once here so the SQL LOWER(content) comparison
        // collates correctly. The leading '$' anchors to the cashtag prefix
        // and rejects accidental substring matches (e.g. won't match "BTCUSD"
        // in body text — only "$BTC" or "$btc" etc.).
        final String pattern = "%$" + normalized.toLowerCase() + "%";

        if (scope == CashtagScope.GLOBAL) {
            return getGlobalCashtagPostsCached(normalized, pattern, limit);
        }

        // NETWORK scope: viewerId is guaranteed non-null by the controller.
        Set<Long> followingIds = followRepository.findAllFollowingIds(viewerId);
        if (followingIds.isEmpty()) {
            return List.of();
        }

        List<Post> rows = postRepository.findByCashtagNetwork(pattern, followingIds, limit);
        return filterBlocksAndMap(rows, viewerId);
    }

    /**
     * Global-scope path with Redis read-through cache. Cache misses serialize
     * the response list as JSON and write back with the 2-min TTL.
     */
    private List<PostResponse> getGlobalCashtagPostsCached(String normalizedSymbol,
                                                           String pattern,
                                                           int limit) {
        final String cacheKey = CASHTAG_CACHE_KEY_PREFIX + normalizedSymbol + ":" + limit;

        // Cache read — best-effort. Redis outage falls through to the DB path.
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return objectMapper.readValue(cached, new TypeReference<List<PostResponse>>() {});
            }
        } catch (Exception e) {
            log.warn("Cashtag cache read failed for key={}, falling through to DB: {}",
                    cacheKey, e.getMessage());
        }

        List<Post> rows = postRepository.findByCashtagGlobal(pattern, limit);
        // No viewerId on global scope → no block filter possible.
        List<PostResponse> responses = rows.stream().map(this::toResponse).toList();

        // Cache write — best-effort. Failure is non-fatal (next request rebuilds).
        try {
            String json = objectMapper.writeValueAsString(responses);
            redisTemplate.opsForValue().set(cacheKey, json, CASHTAG_CACHE_TTL);
        } catch (Exception e) {
            log.warn("Cashtag cache write failed for key={}: {}", cacheKey, e.getMessage());
        }

        return responses;
    }

    /**
     * Mirrors {@code FeedService}'s post-fetch block filter: drops any post
     * authored by a user who has a {@code blocks} row in either direction with
     * the viewer. Result size may be less than {@code limit} after filtering —
     * over-fetch-to-compensate is intentionally NOT implemented (see Prompt B
     * Flag 3 decision).
     */
    private List<PostResponse> filterBlocksAndMap(List<Post> rows, Long viewerId) {
        List<PostResponse> out = new ArrayList<>(rows.size());
        for (Post p : rows) {
            if (blockRepository.existsBlockInEitherDirection(viewerId, p.getAuthorId())) {
                continue;
            }
            out.add(toResponse(p));
        }
        return out;
    }
}
