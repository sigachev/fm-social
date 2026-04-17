package com.finmates.social.profile;

import com.finmates.social.common.security.AuthenticatedUser;
import com.finmates.social.profile.dto.ProfilePublicResponse;
import com.finmates.social.profile.dto.ProfileResponse;
import com.finmates.social.profile.dto.ProfileUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/profiles")
@PreAuthorize("isAuthenticated()")
@Tag(name = "Profiles", description = "User profiles — own profile management and public profile viewing")
public class ProfileController {

    private final ProfileService profileService;
    private final AuthenticatedUser authenticatedUser;

    public ProfileController(ProfileService profileService, AuthenticatedUser authenticatedUser) {
        this.profileService = profileService;
        this.authenticatedUser = authenticatedUser;
    }

    @GetMapping("/me")
    @Operation(summary = "Get own profile (all fields)")
    @ApiResponse(responseCode = "200", description = "Own profile with all settings visible")
    @ApiResponse(responseCode = "404", description = "Profile not yet created")
    public ProfileResponse getOwnProfile() {
        Long userId = authenticatedUser.currentUserId();
        return profileService.getOwnProfile(userId);
    }

    @PutMapping("/me")
    @Operation(summary = "Update own profile (null fields are ignored)")
    @ApiResponse(responseCode = "200", description = "Profile updated")
    public ProfileResponse updateOwnProfile(@Valid @RequestBody ProfileUpdateRequest req) {
        Long userId = authenticatedUser.currentUserId();
        return profileService.updateProfile(userId, req);
    }

    @GetMapping("/{userId}")
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
    @Operation(summary = "Get public profile by username — no auth required (requires cross-service username resolution)")
    @ApiResponse(responseCode = "501", description = "Not implemented — username resolution deferred to Prompt 5")
    public ResponseEntity<Void> getPublicProfileByUsername(@PathVariable String username) {
        // TODO Prompt 5: resolve username → userId via finmates-main /api/internal/users/by-username
        // then call profileService.getPublicProfile(null, userId)
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
