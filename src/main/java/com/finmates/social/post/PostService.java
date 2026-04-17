package com.finmates.social.post;

import com.finmates.social.common.PageResponse;
import com.finmates.social.common.exception.EditWindowExpiredException;
import com.finmates.social.common.exception.ForbiddenActionException;
import com.finmates.social.common.exception.ResourceNotFoundException;
import com.finmates.social.edit.PostEdit;
import com.finmates.social.edit.PostEditRepository;
import com.finmates.social.feed.FeedService;
import com.finmates.social.post.dto.PostCreateRequest;
import com.finmates.social.post.dto.PostResponse;
import com.finmates.social.post.dto.PostUpdateRequest;
import com.finmates.social.upload.S3Service;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@Transactional(readOnly = true)
public class PostService {

    private final PostRepository postRepository;
    private final PostEditRepository postEditRepository;
    private final S3Service s3Service;
    private final FeedService feedService;

    @Value("${finmates.edit-window-minutes}")
    private int editWindowMinutes;

    public PostService(PostRepository postRepository,
                       PostEditRepository postEditRepository,
                       S3Service s3Service,
                       FeedService feedService) {
        this.postRepository = postRepository;
        this.postEditRepository = postEditRepository;
        this.s3Service = s3Service;
        this.feedService = feedService;
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
                    // Extract filename (uuid.ext) from pending key
                    String filename = pendingKey.substring(pendingKey.lastIndexOf('/') + 1);
                    String finalKey = "posts/" + post.getId() + "/" + filename;
                    s3Service.copy(pendingKey, finalKey);
                    finalKeys.add(finalKey);
                    successfullyCopied.add(pendingKey);
                } catch (Exception e) {
                    log.error("Failed to promote S3 key {} for post {}: {}",
                            pendingKey, post.getId(), e.getMessage());
                    // continue — keep whatever succeeded
                }
            }

            // Step 8: Update post with final keys (even if some failed — post is still created)
            post.setMediaKeys(finalKeys);
            post = postRepository.save(post);

            // Step 9: Delete pending copies (best-effort — failures are WARN-and-swallow)
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

    public PageResponse<PostResponse> getPostsByUser(Long authorId, Pageable pageable) {
        return PageResponse.from(postRepository.findActiveByAuthorId(authorId, pageable).map(this::toResponse));
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
     * Public alias used by FeedController to convert a Post fetched outside this service.
     */
    public PostResponse toPublicResponse(Post post) {
        return toResponse(post);
    }

    /**
     * Maps a Post entity to a PostResponse, generating fresh presigned GET URLs for each media key.
     * Returns empty list for mediaUrls when the post has no media.
     */
    public PostResponse toResponse(Post post) {
        List<String> mediaUrls = post.getMediaKeys().stream()
                .map(s3Service::createPresignedGet)
                .toList();

        return new PostResponse(
                post.getId(),
                post.getAuthorId(),
                post.getAuthorUsername(),
                post.getContent(),
                mediaUrls,
                post.getStatus(),
                post.getVisibility(),
                post.getCommentCount(),
                post.getReactionCount(),
                post.getEditCount(),
                post.getLastEditedAt(),
                post.getCreatedAt(),
                post.getUpdatedAt()
        );
    }
}
