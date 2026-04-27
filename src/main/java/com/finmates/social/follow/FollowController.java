package com.finmates.social.follow;

import com.finmates.social.common.PageResponse;
import com.finmates.social.common.security.AuthenticatedUser;
import com.finmates.social.connections.ConnectionsPermissionService;
import com.finmates.social.connections.Scope;
import com.finmates.social.follow.dto.FollowResponse;
import com.finmates.social.follow.dto.FollowStatsResponse;
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
    private final ConnectionsPermissionService permissionService;

    public FollowController(FollowService followService,
                            AuthenticatedUser authenticatedUser,
                            ConnectionsPermissionService permissionService) {
        this.followService = followService;
        this.authenticatedUser = authenticatedUser;
        this.permissionService = permissionService;
    }

    @PostMapping("/{userId}")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Follow (or request to follow) a user",
            description = "If the target's profile is private, the row is created with status PENDING. " +
                    "Otherwise it goes ACTIVE and triggers feed backfill. Idempotent — re-following an " +
                    "already-active or already-pending relationship returns the existing row, never a duplicate.")
    @ApiResponse(responseCode = "201", description = "Now following (ACTIVE) or requested (PENDING) — see response.status")
    @ApiResponse(responseCode = "403", description = "Cannot follow yourself, or a block exists between these users")
    @ApiResponse(responseCode = "404", description = "Target user has no profile")
    public FollowResponse follow(@PathVariable Long userId) {
        Long followerId = authenticatedUser.currentUserId();
        return followService.follow(followerId, userId);
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Unfollow a user (also cancels a pending request to that user)")
    @ApiResponse(responseCode = "204", description = "Unfollowed / cancelled / no-op (idempotent — status-agnostic delete)")
    public void unfollow(@PathVariable Long userId) {
        Long followerId = authenticatedUser.currentUserId();
        followService.unfollow(followerId, userId);
    }

    // ── Pending follow requests (PENDING-state subspace of the follow graph) ───────────────────

    @PostMapping("/requests/{followerUserId}/accept")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Accept a pending follow request",
            description = "Caller is the followee — accepting a request from {followerUserId}. " +
                    "Flips the row from PENDING to ACTIVE and fires feed backfill of the caller's recent posts " +
                    "into the requester's feed.")
    @ApiResponse(responseCode = "200", description = "Accepted; row is now ACTIVE")
    @ApiResponse(responseCode = "400", description = "Row is already ACTIVE — nothing to accept")
    @ApiResponse(responseCode = "404", description = "No follow row exists from {followerUserId} to current user")
    public FollowResponse acceptRequest(@PathVariable Long followerUserId) {
        Long followeeId = authenticatedUser.currentUserId();
        return followService.accept(followeeId, followerUserId);
    }

    @PostMapping("/requests/{followerUserId}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Reject a pending follow request",
            description = "Deletes the PENDING row entirely. The original requester can re-request later. " +
                    "ACTIVE rows cannot be rejected — use unfollow or block instead.")
    @ApiResponse(responseCode = "204", description = "Rejected; row deleted")
    @ApiResponse(responseCode = "400", description = "Row is ACTIVE — use unfollow or block instead")
    @ApiResponse(responseCode = "404", description = "No follow row exists from {followerUserId} to current user")
    public void rejectRequest(@PathVariable Long followerUserId) {
        Long followeeId = authenticatedUser.currentUserId();
        followService.reject(followeeId, followerUserId);
    }

    @GetMapping("/requests/incoming")
    @Operation(summary = "Pending follow requests waiting for me to accept (paginated)")
    public PageResponse<FollowResponse> getIncomingRequests(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = authenticatedUser.currentUserId();
        size = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return followService.getPendingIncoming(userId, pageable);
    }

    @GetMapping("/requests/outgoing")
    @Operation(summary = "My pending follow requests sent to private profiles (paginated)")
    public PageResponse<FollowResponse> getOutgoingRequests(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = authenticatedUser.currentUserId();
        size = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return followService.getPendingOutgoing(userId, pageable);
    }

    @GetMapping("/me/stats")
    @Operation(summary = "Aggregate connection counts for the current user",
            description = "{ followers, following, pendingIncoming, pendingOutgoing, mates } — " +
                    "followers/following are ACTIVE only; mates are mutual ACTIVE follows.")
    public FollowStatsResponse getMyStats() {
        Long userId = authenticatedUser.currentUserId();
        return followService.getStats(userId);
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
    @ApiResponse(responseCode = "200", description = "Following list")
    @ApiResponse(responseCode = "403", description = "Target profile is private and viewer is not a mate")
    public PageResponse<FollowResponse> getUserFollowing(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long viewerId = authenticatedUser.currentUserId();
        permissionService.requireCanView(viewerId, userId, Scope.FOLLOW_LISTS);
        size = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return followService.getFollowing(userId, pageable);
    }

    @GetMapping("/{userId}/followers")
    @Operation(summary = "Users following a given user (paginated)")
    @ApiResponse(responseCode = "200", description = "Followers list")
    @ApiResponse(responseCode = "403", description = "Target profile is private and viewer is not a mate")
    public PageResponse<FollowResponse> getUserFollowers(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long viewerId = authenticatedUser.currentUserId();
        permissionService.requireCanView(viewerId, userId, Scope.FOLLOW_LISTS);
        size = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return followService.getFollowers(userId, pageable);
    }

    @GetMapping("/me/relationship/{userId}")
    @Operation(summary = "Check follow relationship between current user and target user")
    @ApiResponse(responseCode = "200",
            description = "{ isFollowing, isFollowedBy, isPendingOutgoing, isPendingIncoming } — " +
                    "follow flags are ACTIVE only; pending flags surface a sent or received request.")
    public RelationshipResponse getRelationship(@PathVariable Long userId) {
        Long viewerId = authenticatedUser.currentUserId();
        return followService.getRelationship(viewerId, userId);
    }
}
