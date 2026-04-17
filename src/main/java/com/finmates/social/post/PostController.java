package com.finmates.social.post;

import com.finmates.social.common.PageResponse;
import com.finmates.social.common.security.AuthenticatedUser;
import com.finmates.social.post.dto.PostCreateRequest;
import com.finmates.social.post.dto.PostResponse;
import com.finmates.social.post.dto.PostUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/posts")
@PreAuthorize("isAuthenticated()")
@Tag(name = "Posts", description = "Create, edit, and delete posts")
public class PostController {

    private final PostService postService;
    private final AuthenticatedUser authenticatedUser;

    public PostController(PostService postService, AuthenticatedUser authenticatedUser) {
        this.postService = postService;
        this.authenticatedUser = authenticatedUser;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new post")
    @ApiResponse(responseCode = "201", description = "Post created")
    @ApiResponse(responseCode = "400", description = "Validation error")
    public PostResponse createPost(@Valid @RequestBody PostCreateRequest req) {
        Long userId = authenticatedUser.currentUserId();
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
    @Operation(summary = "List active posts by a user (paginated)")
    @ApiResponse(responseCode = "200", description = "Page of posts")
    public PageResponse<PostResponse> getPostsByUser(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        size = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return postService.getPostsByUser(userId, pageable);
    }
}
