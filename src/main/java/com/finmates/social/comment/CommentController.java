package com.finmates.social.comment;

import com.finmates.social.common.PageResponse;
import com.finmates.social.common.security.AuthenticatedUser;
import com.finmates.social.comment.dto.CommentCreateRequest;
import com.finmates.social.comment.dto.CommentResponse;
import com.finmates.social.comment.dto.CommentUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/comments")
@PreAuthorize("isAuthenticated()")
@Tag(name = "Comments", description = "Polymorphic comments on posts, portfolios, and assets")
public class CommentController {

    private final CommentService commentService;
    private final AuthenticatedUser authenticatedUser;

    public CommentController(CommentService commentService, AuthenticatedUser authenticatedUser) {
        this.commentService = commentService;
        this.authenticatedUser = authenticatedUser;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a comment on a post, portfolio, or asset")
    @ApiResponse(responseCode = "201", description = "Comment created")
    @ApiResponse(responseCode = "400", description = "Validation error or invalid target combination")
    public CommentResponse createComment(@Valid @RequestBody CommentCreateRequest req) {
        Long userId = authenticatedUser.currentUserId();
        String username = authenticatedUser.currentUsername();
        return commentService.createComment(userId, username, req);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Edit a comment (within 5-minute window)")
    @ApiResponse(responseCode = "200", description = "Comment updated")
    @ApiResponse(responseCode = "403", description = "Not the author")
    @ApiResponse(responseCode = "410", description = "Edit window expired")
    public CommentResponse updateComment(@PathVariable Long id, @Valid @RequestBody CommentUpdateRequest req) {
        Long userId = authenticatedUser.currentUserId();
        return commentService.updateComment(id, userId, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete (soft-remove) a comment")
    @ApiResponse(responseCode = "204", description = "Comment removed")
    @ApiResponse(responseCode = "403", description = "Not the author")
    public void deleteComment(@PathVariable Long id) {
        Long userId = authenticatedUser.currentUserId();
        commentService.deleteComment(id, userId);
    }

    @GetMapping("/post/{postId}")
    @Operation(summary = "List comments on a post (paginated)")
    public PageResponse<CommentResponse> getCommentsByPost(
            @PathVariable Long postId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        size = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return commentService.getCommentsByPost(postId, pageable);
    }

    @GetMapping("/portfolio/{userId}")
    @Operation(summary = "List comments on a user's portfolio")
    public PageResponse<CommentResponse> getCommentsByPortfolio(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        size = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return commentService.getCommentsByPortfolio(userId, pageable);
    }

    @GetMapping("/asset/{symbol}")
    @Operation(summary = "List comments on an asset (e.g. BTC, ETH)")
    public PageResponse<CommentResponse> getCommentsByAsset(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        size = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return commentService.getCommentsByAsset(symbol, pageable);
    }
}
