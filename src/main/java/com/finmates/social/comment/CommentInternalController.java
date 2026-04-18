package com.finmates.social.comment;

import com.finmates.social.comment.dto.CommentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * Internal moderation endpoints for comments.
 * Secured by {@code X-Internal-Secret} header (validated by {@code InternalSecretFilter}).
 */
@RestController
@RequestMapping("/api/internal/comments")
@Tag(name = "Comments (Internal)", description = "Admin comment moderation — internal use only")
public class CommentInternalController {

    private final CommentService commentService;

    public CommentInternalController(CommentService commentService) {
        this.commentService = commentService;
    }

    /**
     * Get any comment by ID regardless of status — used by admin to preview reported content.
     * Returns REMOVED comments (unlike the public endpoint which returns 404 for removed comments).
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get comment by ID (admin preview — includes removed comments)")
    public CommentResponse getComment(@PathVariable Long id) {
        return commentService.getCommentForAdmin(id);
    }

    @PutMapping("/{id}/remove")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Admin-remove a comment (sets status=REMOVED with audit trail)")
    public CommentResponse removeComment(
            @PathVariable Long id,
            @RequestHeader("X-Admin-User-Id") Long adminUserId,
            @RequestParam(required = false) String reason) {
        return commentService.adminRemoveComment(id, adminUserId, reason);
    }

    @PutMapping("/{id}/restore")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Restore a removed comment (sets status=ACTIVE, clears removal audit)")
    public CommentResponse restoreComment(
            @PathVariable Long id,
            @RequestHeader("X-Admin-User-Id") Long adminUserId) {
        return commentService.adminRestoreComment(id, adminUserId);
    }
}
