package com.finmates.social.post;

import com.finmates.social.common.PageResponse;
import com.finmates.social.common.exception.EditWindowExpiredException;
import com.finmates.social.common.exception.ForbiddenActionException;
import com.finmates.social.common.exception.ResourceNotFoundException;
import com.finmates.social.edit.PostEdit;
import com.finmates.social.edit.PostEditRepository;
import com.finmates.social.post.dto.PostCreateRequest;
import com.finmates.social.post.dto.PostResponse;
import com.finmates.social.post.dto.PostUpdateRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Slf4j
@Service
@Transactional(readOnly = true)
public class PostService {

    private final PostRepository postRepository;
    private final PostEditRepository postEditRepository;

    @Value("${finmates.edit-window-minutes}")
    private int editWindowMinutes;

    public PostService(PostRepository postRepository, PostEditRepository postEditRepository) {
        this.postRepository = postRepository;
        this.postEditRepository = postEditRepository;
    }

    @Transactional
    public PostResponse createPost(Long authorId, PostCreateRequest req) {
        Post post = new Post();
        post.setAuthorId(authorId);
        post.setContent(req.getContent());
        post.setMediaKeys(req.getMediaKeys() != null ? req.getMediaKeys() : new java.util.ArrayList<>());
        post.setVisibility(req.getVisibility() != null ? req.getVisibility() : PostVisibility.PUBLIC);
        return toResponse(postRepository.save(post));
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

    public PostResponse toResponse(Post post) {
        return new PostResponse(
                post.getId(),
                post.getAuthorId(),
                post.getContent(),
                post.getMediaKeys(),
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
