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
import com.finmates.social.util.CashtagExtractor;
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

    /** Replies, comments on your post and mentions (optional so slice tests can skip it). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.finmates.social.notification.SocialNotifier socialNotifier;

    private final com.finmates.social.access.AudienceService audience;

    public CommentService(CommentRepository commentRepository,
                          CommentEditRepository commentEditRepository,
                          PostRepository postRepository,
                          com.finmates.social.access.AudienceService audience) {
        this.audience = audience;
        this.commentRepository = commentRepository;
        this.commentEditRepository = commentEditRepository;
        this.postRepository = postRepository;
    }

    @Transactional
    public CommentResponse createComment(Long authorId, String authorUsername, CommentCreateRequest req) {
        // A reply stays on its parent's post, portfolio or token: it can't be used to write somewhere else.
        if (req.getParentId() != null) {
            Comment parent = commentRepository.findById(req.getParentId())
                    .orElseThrow(() -> new ResourceNotFoundException("Comment not found: " + req.getParentId()));
            if (parent.getTargetType() != req.getTargetType() || !java.util.Objects.equals(parent.getTargetId(), req.getTargetId())
                    || !java.util.Objects.equals(parent.getTargetSymbol(), req.getTargetSymbol() == null ? null : req.getTargetSymbol().toUpperCase())) {
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                        "A reply must be on the same post, portfolio or token as the comment it answers");
            }
        }
        requireCanSee(authorId, req.getTargetType(), req.getTargetId());
        requireCanComment(authorId, req.getTargetType(), req.getTargetId());
        Comment comment = new Comment();
        comment.setAuthorId(authorId);
        comment.setAuthorUsername(authorUsername);
        comment.setTargetType(req.getTargetType());
        comment.setTargetId(req.getTargetId());
        comment.setTargetSymbol(req.getTargetSymbol() != null ? req.getTargetSymbol().toUpperCase() : null);
        comment.setParentId(req.getParentId());
        comment.setContent(req.getContent());
        // Mentions tab: index cashtags at write time so the GIN-backed query in
        // Phase 2 stays cheap. Replacement semantics — see CashtagExtractor.
        // Fires for replies (parent_id != null) too; the table is uniform.
        comment.setExtractedCashtags(CashtagExtractor.extract(req.getContent()));

        Comment saved = commentRepository.save(comment);

        // Increment post comment_count
        if (req.getTargetType() == CommentTargetType.POST && req.getTargetId() != null) {
            postRepository.adjustCommentCount(req.getTargetId(), 1);
        }
        if (socialNotifier != null) socialNotifier.commentCreated(saved);

        return toResponse(saved);
    }

    public CommentResponse getComment(Long id) {
        return toResponse(findActiveComment(id));
    }

    /**
     * Comments are visible to whoever can see what they are on: the post (its audience and its author's account) or
     * the portfolio (its owner's portfolio audience). Token comments are public. 404 otherwise, so nothing leaks.
     */
    /**
     * Writing on someone's portfolio ("notes") also needs their notes permission: Everyone, Mates or Off (only the
     * owner). Posts and tokens take comments from anyone who can see them.
     */
    void requireCanComment(Long authorId, CommentTargetType type, Long targetId) {
        if (type != CommentTargetType.PORTFOLIO || targetId == null) return;
        var viewer = audience.viewer(authorId);
        var level = viewer.profile(targetId).map(p -> com.finmates.social.access.AudienceService.of(p.getNotesPermission()))
                .orElse(com.finmates.social.access.AudienceService.Level.ONLY_ME);
        if (!viewer.allows(targetId, level)) {
            throw new com.finmates.social.common.exception.ForbiddenActionException(
                    level == com.finmates.social.access.AudienceService.Level.MATES
                            ? "Only their mates can leave notes here" : "They've turned off notes on their portfolio");
        }
    }

    public void requireCanSee(Long viewerId, CommentTargetType type, Long targetId) {
        if (type == null) return;
        var viewer = audience.viewer(viewerId);
        boolean ok = switch (type) {
            case POST -> targetId != null && postRepository.findById(targetId).map(viewer::canSee).orElse(false);
            case PORTFOLIO -> targetId != null && viewer.canSeePortfolio(targetId);
            case ASSET -> true;
        };
        if (!ok) throw new ResourceNotFoundException("Not found");
    }

    /** Admin preview — returns comment regardless of status (including REMOVED). */
    public CommentResponse getCommentForAdmin(Long id) {
        return toResponse(findComment(id));
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
        // Re-extract on edit so cashtag removals are reflected — replacement,
        // never append. Mirrors the create-path policy above.
        comment.setExtractedCashtags(CashtagExtractor.extract(req.getContent()));
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

    /** Admin moderation: removes a comment with audit trail. */
    @Transactional
    public CommentResponse adminRemoveComment(Long id, Long adminUserId, String reason) {
        Comment comment = findComment(id);
        comment.setStatus(CommentStatus.REMOVED);
        comment.setRemovedAt(java.time.OffsetDateTime.now());
        comment.setRemovedBy(adminUserId);
        comment.setRemovalReason(reason);
        return toResponse(commentRepository.save(comment));
    }

    /** Admin moderation: restores a removed comment and clears removal audit fields. */
    @Transactional
    public CommentResponse adminRestoreComment(Long id, Long adminUserId) {
        Comment comment = findComment(id);
        if (comment.getStatus() != CommentStatus.REMOVED) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "Comment is not removed");
        }
        comment.setStatus(CommentStatus.ACTIVE);
        comment.setRemovedAt(null);
        comment.setRemovedBy(null);
        comment.setRemovalReason(null);
        return toResponse(commentRepository.save(comment));
    }

    public PageResponse<CommentResponse> getCommentsByPost(Long postId, Pageable pageable, Long viewerId) {
        requireCanSee(viewerId, CommentTargetType.POST, postId);
        return PageResponse.from(commentRepository.findActiveByPostId(postId, pageable).map(this::toResponse));
    }

    public PageResponse<CommentResponse> getCommentsByPortfolio(Long ownerUserId, Pageable pageable, Long viewerId) {
        requireCanSee(viewerId, CommentTargetType.PORTFOLIO, ownerUserId);
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
                comment.getUpdatedAt(),
                comment.getRemovedAt(),
                comment.getRemovalReason(),
                comment.getRemovedBy(),
                // replyCount / replyCountAlsoMentioning are Mentions-only;
                // the standard /api/comments/* list endpoints leave them null.
                null,
                null
        );
    }
}
