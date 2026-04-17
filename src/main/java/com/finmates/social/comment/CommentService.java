package com.finmates.social.comment;

import com.finmates.social.common.PageResponse;
import com.finmates.social.common.exception.EditWindowExpiredException;
import com.finmates.social.common.exception.ForbiddenActionException;
import com.finmates.social.common.exception.ResourceNotFoundException;
import com.finmates.social.comment.dto.CommentCreateRequest;
import com.finmates.social.comment.dto.CommentResponse;
import com.finmates.social.comment.dto.CommentUpdateRequest;
import com.finmates.social.edit.CommentEdit;
import com.finmates.social.edit.CommentEditRepository;
import com.finmates.social.post.PostRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Slf4j
@Service
@Transactional(readOnly = true)
public class CommentService {

    private final CommentRepository commentRepository;
    private final CommentEditRepository commentEditRepository;
    private final PostRepository postRepository;

    @Value("${finmates.edit-window-minutes}")
    private int editWindowMinutes;

    public CommentService(CommentRepository commentRepository,
                          CommentEditRepository commentEditRepository,
                          PostRepository postRepository) {
        this.commentRepository = commentRepository;
        this.commentEditRepository = commentEditRepository;
        this.postRepository = postRepository;
    }

    @Transactional
    public CommentResponse createComment(Long authorId, String authorUsername, CommentCreateRequest req) {
        Comment comment = new Comment();
        comment.setAuthorId(authorId);
        comment.setAuthorUsername(authorUsername);
        comment.setTargetType(req.getTargetType());
        comment.setTargetId(req.getTargetId());
        comment.setTargetSymbol(req.getTargetSymbol() != null ? req.getTargetSymbol().toUpperCase() : null);
        comment.setParentId(req.getParentId());
        comment.setContent(req.getContent());

        Comment saved = commentRepository.save(comment);

        // Increment post comment_count
        if (req.getTargetType() == CommentTargetType.POST && req.getTargetId() != null) {
            postRepository.adjustCommentCount(req.getTargetId(), 1);
        }

        return toResponse(saved);
    }

    public CommentResponse getComment(Long id) {
        return toResponse(findActiveComment(id));
    }

    @Transactional
    public CommentResponse updateComment(Long id, Long currentUserId, CommentUpdateRequest req) {
        Comment comment = findComment(id);
        checkOwnership(comment.getAuthorId(), currentUserId);
        checkEditWindow(comment.getCreatedAt());

        CommentEdit edit = new CommentEdit();
        edit.setCommentId(comment.getId());
        edit.setPreviousContent(comment.getContent());
        commentEditRepository.save(edit);

        comment.setContent(req.getContent());
        comment.setEditCount(comment.getEditCount() + 1);
        comment.setLastEditedAt(OffsetDateTime.now());
        return toResponse(commentRepository.save(comment));
    }

    @Transactional
    public void deleteComment(Long id, Long currentUserId) {
        Comment comment = findComment(id);
        checkOwnership(comment.getAuthorId(), currentUserId);
        comment.setStatus(CommentStatus.REMOVED);
        commentRepository.save(comment);

        // Decrement post comment_count
        if (comment.getTargetType() == CommentTargetType.POST && comment.getTargetId() != null) {
            postRepository.adjustCommentCount(comment.getTargetId(), -1);
        }
    }

    public PageResponse<CommentResponse> getCommentsByPost(Long postId, Pageable pageable) {
        return PageResponse.from(commentRepository.findActiveByPostId(postId, pageable).map(this::toResponse));
    }

    public PageResponse<CommentResponse> getCommentsByPortfolio(Long ownerUserId, Pageable pageable) {
        return PageResponse.from(commentRepository.findActiveByPortfolioOwnerId(ownerUserId, pageable).map(this::toResponse));
    }

    public PageResponse<CommentResponse> getCommentsByAsset(String symbol, Pageable pageable) {
        return PageResponse.from(commentRepository.findActiveByAssetSymbol(symbol.toUpperCase(), pageable).map(this::toResponse));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Comment findComment(Long id) {
        return commentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Comment not found: " + id));
    }

    private Comment findActiveComment(Long id) {
        Comment c = findComment(id);
        if (c.getStatus() == CommentStatus.REMOVED) {
            throw new ResourceNotFoundException("Comment not found: " + id);
        }
        return c;
    }

    private void checkOwnership(Long ownerId, Long requesterId) {
        if (!ownerId.equals(requesterId)) {
            throw new ForbiddenActionException("Not authorized to modify this comment");
        }
    }

    private void checkEditWindow(OffsetDateTime createdAt) {
        if (createdAt.plusMinutes(editWindowMinutes).isBefore(OffsetDateTime.now())) {
            throw new EditWindowExpiredException(
                    "Comments can only be edited within " + editWindowMinutes + " minutes of creation");
        }
    }

    public CommentResponse toResponse(Comment comment) {
        return new CommentResponse(
                comment.getId(),
                comment.getAuthorId(),
                comment.getAuthorUsername(),
                comment.getTargetType(),
                comment.getTargetId(),
                comment.getTargetSymbol(),
                comment.getParentId(),
                comment.getContent(),
                comment.getStatus(),
                comment.getReactionCount(),
                comment.getEditCount(),
                comment.getLastEditedAt(),
                comment.getCreatedAt(),
                comment.getUpdatedAt()
        );
    }
}
