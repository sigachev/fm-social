package com.finmates.social.profile;

import com.finmates.social.client.UserLookupCache;
import com.finmates.social.common.security.AuthenticatedUser;
import com.finmates.social.profile.dto.ProfilePublicResponse;
import com.finmates.social.profile.dto.ProfileResponse;
import com.finmates.social.profile.dto.ProfileUpdateRequest;
import com.finmates.social.profile.dto.PublicProfileResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/profiles")
@Tag(name = "Profiles", description = "User profiles — own profile management and public profile viewing")
public class ProfileController {

    private final ProfileService profileService;
    private final AuthenticatedUser authenticatedUser;
    private final UserLookupCache userLookupCache;

    public ProfileController(ProfileService profileService,
                             AuthenticatedUser authenticatedUser,
                             UserLookupCache userLookupCache) {
        this.profileService = profileService;
        this.authenticatedUser = authenticatedUser;
        this.userLookupCache = userLookupCache;
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get own profile (all fields)")
    @ApiResponse(responseCode = "200", description = "Own profile with all settings visible")
    @ApiResponse(responseCode = "404", description = "Profile not yet created")
    public ProfileResponse getOwnProfile() {
        Long userId = authenticatedUser.currentUserId();
        return profileService.getOwnProfile(userId);
    }

    @PutMapping("/me")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Update own profile (null fields are ignored)")
    @ApiResponse(responseCode = "200", description = "Profile updated")
    public ProfileResponse updateOwnProfile(@Valid @RequestBody ProfileUpdateRequest req) {
        Long userId = authenticatedUser.currentUserId();
        return profileService.updateProfile(userId, req);
    }

    @GetMapping("/{userId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get another user's profile (filtered by their visibility settings)")
    @ApiResponse(responseCode = "200", description = "Profile — fields filtered by visibility")
    @ApiResponse(responseCode = "403", description = "Profile is private")
    @ApiResponse(responseCode = "404", description = "Profile not found")
    public ProfilePublicResponse getUserProfile(@PathVariable Long userId) {
        Long viewerId = authenticatedUser.currentUserId();
        return profileService.getPublicProfile(viewerId, userId);
    }

    @GetMapping("/{username}/public")
    @PreAuthorize("permitAll()")
    @Operation(summary = "Get public profile by username — no auth required")
    @ApiResponse(responseCode = "200", description = "Public profile")
    @ApiResponse(responseCode = "404", description = "User not found or profile is private")
    @ApiResponse(responseCode = "503", description = "User identity service unavailable")
    public ResponseEntity<PublicProfileResponse> getPublicProfileByUsername(
            @PathVariable String username) {

        // Viewer may be unauthenticated — extract userId gracefully
        Long viewerId = null;
        try {
            viewerId = authenticatedUser.currentUserId();
        } catch (Exception ignored) {
            // unauthenticated request — viewerId remains null
        }

        // Resolve username → userId via finmates-main (cached, 5-min TTL)
        // NOTE: requires GET /api/internal/users/by-username/{username}/summary in finmates-main.
        // Returns empty Optional gracefully if main is down.
        java.util.Optional<UserLookupCache.UserSummary> userSummaryOpt =
                userLookupCache.getByUsername(username);

        if (userSummaryOpt.isEmpty()) {
            // Main service unreachable or user not found — return 404
            return ResponseEntity.notFound().build();
        }

        UserLookupCache.UserSummary userSummary = userSummaryOpt.get();

        if (!userSummary.isActive()) {
            return ResponseEntity.notFound().build();
        }

        Long targetUserId = userSummary.userId();
        if (targetUserId == null) {
            // main returned a summary without a userId — user exists in Keycloak but not yet in main DB
            return ResponseEntity.notFound().build();
        }

        return profileService.buildPublicProfileResponse(viewerId, targetUserId, userSummary)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
