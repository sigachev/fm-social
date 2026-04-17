package com.finmates.social.follow;

import com.finmates.social.common.PageResponse;
import com.finmates.social.common.security.AuthenticatedUser;
import com.finmates.social.follow.dto.FollowResponse;
import com.finmates.social.follow.dto.RelationshipResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/follows")
@PreAuthorize("isAuthenticated()")
@Tag(name = "Follows", description = "Unidirectional follow graph")
public class FollowController {

    private final FollowService followService;
    private final AuthenticatedUser authenticatedUser;

    public FollowController(FollowService followService, AuthenticatedUser authenticatedUser) {
        this.followService = followService;
        this.authenticatedUser = authenticatedUser;
    }

    @PostMapping("/{userId}")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Follow a user")
    @ApiResponse(responseCode = "201", description = "Now following")
    @ApiResponse(responseCode = "400", description = "Cannot follow yourself")
    @ApiResponse(responseCode = "403", description = "Block exists between these users")
    @ApiResponse(responseCode = "409", description = "Already following")
    public FollowResponse follow(@PathVariable Long userId) {
        Long followerId = authenticatedUser.currentUserId();
        return followService.follow(followerId, userId);
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Unfollow a user")
    @ApiResponse(responseCode = "204", description = "Unfollowed (or was not following — idempotent)")
    public void unfollow(@PathVariable Long userId) {
        Long followerId = authenticatedUser.currentUserId();
        followService.unfollow(followerId, userId);
    }

    @GetMapping("/me/following")
    @Operation(summary = "Users I am following (paginated)")
    public PageResponse<FollowResponse> getMyFollowing(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = authenticatedUser.currentUserId();
        size = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return followService.getFollowing(userId, pageable);
    }

    @GetMapping("/me/followers")
    @Operation(summary = "Users following me (paginated)")
    public PageResponse<FollowResponse> getMyFollowers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = authenticatedUser.currentUserId();
        size = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return followService.getFollowers(userId, pageable);
    }

    @GetMapping("/{userId}/following")
    @Operation(summary = "Users a given user is following (paginated)")
    public PageResponse<FollowResponse> getUserFollowing(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        size = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return followService.getFollowing(userId, pageable);
    }

    @GetMapping("/{userId}/followers")
    @Operation(summary = "Users following a given user (paginated)")
    public PageResponse<FollowResponse> getUserFollowers(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        size = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return followService.getFollowers(userId, pageable);
    }

    @GetMapping("/me/relationship/{userId}")
    @Operation(summary = "Check follow relationship between current user and target user")
    @ApiResponse(responseCode = "200", description = "{ isFollowing: bool, isFollowedBy: bool }")
    public RelationshipResponse getRelationship(@PathVariable Long userId) {
        Long viewerId = authenticatedUser.currentUserId();
        return followService.getRelationship(viewerId, userId);
    }
}
