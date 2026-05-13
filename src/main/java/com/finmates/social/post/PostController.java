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
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

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

    /**
     * Recent posts tagged with an asset cashtag (e.g. $BTC), for the token-detail
     * social column. Mixed-auth endpoint: anonymous viewers get {@code scope=global}
     * by default; authenticated viewers get {@code scope=network} by default.
     * {@code permitAll()} overrides the class-level {@code isAuthenticated()};
     * scope=network with no JWT yields 401 from the inline check below.
     */
    @GetMapping("/by-cashtag")
    @PreAuthorize("permitAll()")
    @Operation(summary = "Recent posts tagged with a cashtag (network or global scope)")
    @ApiResponse(responseCode = "200", description = "Posts matching the cashtag")
    @ApiResponse(responseCode = "400", description = "Missing or invalid params")
    @ApiResponse(responseCode = "401", description = "scope=network requires authentication")
    public List<PostResponse> getPostsByCashtag(
            @RequestParam String symbol,
            @RequestParam(required = false) String scope,
            @RequestParam(defaultValue = "6") int limit) {

        if (symbol == null || symbol.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "symbol is required");
        }
        // Clamp to [1, 20]. 20 matches the documented max in CLAUDE.md / contract.
        int effectiveLimit = Math.max(1, Math.min(20, limit));

        Long viewerId = authenticatedUser.currentUserIdOrNull();

        // Default-scope inference happens HERE in the controller (not on the
        // frontend) so the contract is consistent regardless of caller. Auth
        // present → network; absent → global.
        PostService.CashtagScope effectiveScope = resolveScope(scope, viewerId);
        if (effectiveScope == PostService.CashtagScope.NETWORK && viewerId == null) {
            // Explicit 401 (not 403) per Phase 1 verification contract.
            // The endpoint is permitAll() at the URL layer to allow anonymous
            // scope=global, so the filter chain doesn't auto-401 — we surface
            // the auth requirement here for the network branch only.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "scope=network requires authentication");
        }

        return postService.getPostsByCashtag(symbol, effectiveScope, viewerId, effectiveLimit);
    }

    private PostService.CashtagScope resolveScope(String scopeParam, Long viewerId) {
        if (scopeParam == null || scopeParam.isBlank()) {
            return viewerId != null
                    ? PostService.CashtagScope.NETWORK
                    : PostService.CashtagScope.GLOBAL;
        }
        return switch (scopeParam.trim().toLowerCase()) {
            case "network" -> PostService.CashtagScope.NETWORK;
            case "global"  -> PostService.CashtagScope.GLOBAL;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "scope must be 'network' or 'global'");
        };
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
