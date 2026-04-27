package com.finmates.social.post;

import com.finmates.social.common.security.AuthenticatedUser;
import com.finmates.social.connections.ConnectionsPermissionService;
import com.finmates.social.connections.Scope;
import com.finmates.social.feed.FeedResponse;
import com.finmates.social.moderation.UserBanCheckService;
import com.finmates.social.post.dto.PostCreateRequest;
import com.finmates.social.post.dto.PostResponse;
import com.finmates.social.post.dto.PostUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/posts")
@PreAuthorize("isAuthenticated()")
@Tag(name = "Posts", description = "Create, edit, and delete posts")
public class PostController {

    private final PostService postService;
    private final AuthenticatedUser authenticatedUser;
    private final UserBanCheckService userBanCheckService;
    private final ConnectionsPermissionService permissionService;

    public PostController(PostService postService,
                          AuthenticatedUser authenticatedUser,
                          UserBanCheckService userBanCheckService,
                          ConnectionsPermissionService permissionService) {
        this.postService = postService;
        this.authenticatedUser = authenticatedUser;
        this.userBanCheckService = userBanCheckService;
        this.permissionService = permissionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new post")
    @ApiResponse(responseCode = "201", description = "Post created")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "403", description = "Account suspended or banned")
    public PostResponse createPost(@Valid @RequestBody PostCreateRequest req) {
        Long userId = authenticatedUser.currentUserId();
        userBanCheckService.assertNotBanned(userId);
        String username = authenticatedUser.currentUsername();
        return postService.createPost(userId, username, req);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get post by ID")
    @ApiResponse(responseCode = "200", description = "Post found")
    @ApiResponse(responseCode = "404", description = "Post not found")
    public PostResponse getPost(@PathVariable Long id) {
        return postService.getPost(id);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Edit post content (within 5-minute window)")
    @ApiResponse(responseCode = "200", description = "Post updated")
    @ApiResponse(responseCode = "403", description = "Not the author")
    @ApiResponse(responseCode = "410", description = "Edit window expired")
    public PostResponse updatePost(@PathVariable Long id, @Valid @RequestBody PostUpdateRequest req) {
        Long userId = authenticatedUser.currentUserId();
        return postService.updatePost(id, userId, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete (soft-remove) a post")
    @ApiResponse(responseCode = "204", description = "Post removed")
    @ApiResponse(responseCode = "403", description = "Not the author")
    @ApiResponse(responseCode = "404", description = "Post not found")
    public void deletePost(@PathVariable Long id) {
        Long userId = authenticatedUser.currentUserId();
        postService.deletePost(id, userId);
    }

    @GetMapping("/user/{userId}")
    @Operation(summary = "List active posts by a user (cursor-based, same shape as /api/feed)")
    @ApiResponse(responseCode = "200", description = "Feed-shaped response: { posts, nextCursor, hasMore }")
    @ApiResponse(responseCode = "403", description = "Target profile is private and viewer is not a mate")
    public FeedResponse getPostsByUser(
            @PathVariable Long userId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int limit) {
        Long viewerId = authenticatedUser.currentUserId();
        permissionService.requireCanView(viewerId, userId, Scope.FEED);
        limit = Math.min(limit, 50);
        return postService.getUserPostsFeed(userId, cursor, limit);
    }
}
