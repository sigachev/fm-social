package com.finmates.social.post;

import com.finmates.social.post.dto.PostResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * Internal moderation endpoints for posts.
 * Secured by {@code X-Internal-Secret} header (validated by {@code InternalSecretFilter}).
 */
@RestController
@RequestMapping("/api/internal/posts")
@Tag(name = "Posts (Internal)", description = "Admin post moderation — internal use only")
public class PostInternalController {

    private final PostService postService;

    public PostInternalController(PostService postService) {
        this.postService = postService;
    }

    /**
     * Admin-remove a post: sets status=REMOVED and records who removed it and why.
     * Unlike the user-delete endpoint, this records removal audit fields.
     */
    @PutMapping("/{id}/remove")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Admin-remove a post (sets status=REMOVED with audit trail)")
    public PostResponse removePost(
            @PathVariable Long id,
            @RequestHeader("X-Admin-User-Id") Long adminUserId,
            @RequestParam(required = false) String reason) {
        return postService.adminRemovePost(id, adminUserId, reason);
    }

    /**
     * Restore a previously removed post: resets status=ACTIVE and clears removal fields.
     */
    @PutMapping("/{id}/restore")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Restore a removed post (sets status=ACTIVE, clears removal audit)")
    public PostResponse restorePost(
            @PathVariable Long id,
            @RequestHeader("X-Admin-User-Id") Long adminUserId) {
        return postService.adminRestorePost(id, adminUserId);
    }
}
