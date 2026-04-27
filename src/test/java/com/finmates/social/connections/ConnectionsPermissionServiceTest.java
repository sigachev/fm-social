package com.finmates.social.connections;

import com.finmates.social.common.exception.ForbiddenActionException;
import com.finmates.social.follow.FollowRepository;
import com.finmates.social.follow.FollowStatus;
import com.finmates.social.profile.Profile;
import com.finmates.social.profile.ProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito unit tests — no Spring context. Covers each branch of
 * {@link ConnectionsPermissionService#canView}, the {@code requireCanView}
 * throwing variant, and {@link ConnectionsPermissionService#isMate}.
 *
 * <p>Repository @DataJpaTest tests for the new query methods are deferred to
 * Phase 3 because fm-social has no existing JPA test infrastructure (only one
 * context-loads test) and standing up Testcontainers PG is its own setup task.</p>
 */
@ExtendWith(MockitoExtension.class)
class ConnectionsPermissionServiceTest {

    @Mock FollowRepository followRepository;
    @Mock ProfileRepository profileRepository;

    @InjectMocks ConnectionsPermissionService service;

    private static final Long VIEWER  = 1L;
    private static final Long TARGET  = 2L;
    private static final Long STRANGER = 3L;

    private Profile publicProfile;
    private Profile privateProfile;

    @BeforeEach
    void setUp() {
        publicProfile = new Profile();
        publicProfile.setUserId(TARGET);
        publicProfile.setPrivate(false);

        privateProfile = new Profile();
        privateProfile.setUserId(TARGET);
        privateProfile.setPrivate(true);
    }

    // ── canView branches ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("canView")
    class CanView {

        @Test
        @DisplayName("self-view is always allowed for any scope")
        void selfView() {
            // No repository calls expected — should short-circuit on viewer == target
            assertThat(service.canView(VIEWER, VIEWER, Scope.PROFILE_BASIC)).isTrue();
            assertThat(service.canView(VIEWER, VIEWER, Scope.FOLLOW_LISTS)).isTrue();
            assertThat(service.canView(VIEWER, VIEWER, Scope.FEED)).isTrue();
            assertThat(service.canView(VIEWER, VIEWER, Scope.PORTFOLIO)).isTrue();
            verify(profileRepository, never()).findById(any());
            verify(followRepository, never()).existsByFollowerIdAndFollowedIdAndStatus(any(), any(), any());
        }

        @Test
        @DisplayName("PROFILE_BASIC is always allowed regardless of privacy")
        void profileBasicAlwaysAllowed() {
            // Even with no profile loaded — short-circuit before hitting the DB
            assertThat(service.canView(STRANGER, TARGET, Scope.PROFILE_BASIC)).isTrue();
            assertThat(service.canView(null, TARGET, Scope.PROFILE_BASIC)).isTrue();
            verify(profileRepository, never()).findById(any());
        }

        @Test
        @DisplayName("public profile: any scope visible to authenticated and anonymous viewers")
        void publicProfileVisibleToAll() {
            when(profileRepository.findById(TARGET)).thenReturn(Optional.of(publicProfile));

            assertThat(service.canView(STRANGER, TARGET, Scope.FOLLOW_LISTS)).isTrue();
            assertThat(service.canView(STRANGER, TARGET, Scope.FEED)).isTrue();
            assertThat(service.canView(STRANGER, TARGET, Scope.PORTFOLIO)).isTrue();
            assertThat(service.canView(null, TARGET, Scope.FEED)).isTrue();
        }

        @Test
        @DisplayName("private profile: anonymous viewer denied for non-PROFILE_BASIC scopes")
        void privateProfileDeniesAnonymous() {
            when(profileRepository.findById(TARGET)).thenReturn(Optional.of(privateProfile));

            assertThat(service.canView(null, TARGET, Scope.FOLLOW_LISTS)).isFalse();
            assertThat(service.canView(null, TARGET, Scope.FEED)).isFalse();
            assertThat(service.canView(null, TARGET, Scope.PORTFOLIO)).isFalse();
        }

        @Test
        @DisplayName("private profile: non-mate viewer denied")
        void privateProfileDeniesNonMate() {
            when(profileRepository.findById(TARGET)).thenReturn(Optional.of(privateProfile));
            // viewer follows target, but target does NOT follow back → not mate
            when(followRepository.existsByFollowerIdAndFollowedIdAndStatus(STRANGER, TARGET, FollowStatus.ACTIVE))
                    .thenReturn(true);
            when(followRepository.existsByFollowerIdAndFollowedIdAndStatus(TARGET, STRANGER, FollowStatus.ACTIVE))
                    .thenReturn(false);

            assertThat(service.canView(STRANGER, TARGET, Scope.FOLLOW_LISTS)).isFalse();
        }

        @Test
        @DisplayName("private profile: mate viewer allowed for all scopes")
        void privateProfileAllowsMate() {
            when(profileRepository.findById(TARGET)).thenReturn(Optional.of(privateProfile));
            when(followRepository.existsByFollowerIdAndFollowedIdAndStatus(STRANGER, TARGET, FollowStatus.ACTIVE))
                    .thenReturn(true);
            when(followRepository.existsByFollowerIdAndFollowedIdAndStatus(TARGET, STRANGER, FollowStatus.ACTIVE))
                    .thenReturn(true);

            assertThat(service.canView(STRANGER, TARGET, Scope.FOLLOW_LISTS)).isTrue();
            assertThat(service.canView(STRANGER, TARGET, Scope.FEED)).isTrue();
            assertThat(service.canView(STRANGER, TARGET, Scope.PORTFOLIO)).isTrue();
        }

        @Test
        @DisplayName("private profile: PENDING follow does not satisfy the mate check")
        void privateProfilePendingFollowIsNotMate() {
            when(profileRepository.findById(TARGET)).thenReturn(Optional.of(privateProfile));
            // existsByFollowerIdAndFollowedIdAndStatus only matches ACTIVE — PENDING returns false here
            when(followRepository.existsByFollowerIdAndFollowedIdAndStatus(STRANGER, TARGET, FollowStatus.ACTIVE))
                    .thenReturn(false);

            assertThat(service.canView(STRANGER, TARGET, Scope.FEED)).isFalse();
        }

        @Test
        @DisplayName("missing target profile: fails closed")
        void missingProfileFailsClosed() {
            when(profileRepository.findById(TARGET)).thenReturn(Optional.empty());

            assertThat(service.canView(VIEWER, TARGET, Scope.FOLLOW_LISTS)).isFalse();
            assertThat(service.canView(VIEWER, TARGET, Scope.FEED)).isFalse();
            // PROFILE_BASIC still allowed — short-circuit before DB lookup
            assertThat(service.canView(VIEWER, TARGET, Scope.PROFILE_BASIC)).isTrue();
        }

        @Test
        @DisplayName("null target or null scope: denied")
        void nullArgsDenied() {
            assertThat(service.canView(VIEWER, null, Scope.FEED)).isFalse();
            assertThat(service.canView(VIEWER, TARGET, null)).isFalse();
        }
    }

    // ── requireCanView ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("requireCanView")
    class RequireCanView {

        @Test
        @DisplayName("does nothing when canView returns true")
        void allowsWhenAllowed() {
            when(profileRepository.findById(TARGET)).thenReturn(Optional.of(publicProfile));
            // No throw expected
            service.requireCanView(STRANGER, TARGET, Scope.FEED);
        }

        @Test
        @DisplayName("throws ForbiddenActionException when canView returns false")
        void throwsWhenDenied() {
            when(profileRepository.findById(TARGET)).thenReturn(Optional.of(privateProfile));
            when(followRepository.existsByFollowerIdAndFollowedIdAndStatus(eq(STRANGER), eq(TARGET), any()))
                    .thenReturn(false);

            assertThatThrownBy(() -> service.requireCanView(STRANGER, TARGET, Scope.FEED))
                    .isInstanceOf(ForbiddenActionException.class)
                    .hasMessageContaining("FEED")
                    .hasMessageContaining(TARGET.toString());
        }
    }

    // ── isMate ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("isMate")
    class IsMate {

        @Test
        @DisplayName("returns true only when both directions are ACTIVE")
        void mutualActive() {
            when(followRepository.existsByFollowerIdAndFollowedIdAndStatus(VIEWER, TARGET, FollowStatus.ACTIVE))
                    .thenReturn(true);
            when(followRepository.existsByFollowerIdAndFollowedIdAndStatus(TARGET, VIEWER, FollowStatus.ACTIVE))
                    .thenReturn(true);

            assertThat(service.isMate(VIEWER, TARGET)).isTrue();
        }

        @Test
        @DisplayName("returns false when only one direction is active")
        void oneSidedFollow() {
            when(followRepository.existsByFollowerIdAndFollowedIdAndStatus(VIEWER, TARGET, FollowStatus.ACTIVE))
                    .thenReturn(true);
            when(followRepository.existsByFollowerIdAndFollowedIdAndStatus(TARGET, VIEWER, FollowStatus.ACTIVE))
                    .thenReturn(false);

            assertThat(service.isMate(VIEWER, TARGET)).isFalse();
        }

        @Test
        @DisplayName("returns false when first direction is missing — short-circuits")
        void shortCircuitsOnFirstMiss() {
            when(followRepository.existsByFollowerIdAndFollowedIdAndStatus(VIEWER, TARGET, FollowStatus.ACTIVE))
                    .thenReturn(false);

            assertThat(service.isMate(VIEWER, TARGET)).isFalse();
            // Reverse direction never queried
            verify(followRepository, never())
                    .existsByFollowerIdAndFollowedIdAndStatus(eq(TARGET), eq(VIEWER), any());
        }

        @Test
        @DisplayName("self is never their own mate")
        void selfIsNotMate() {
            assertThat(service.isMate(VIEWER, VIEWER)).isFalse();
            verify(followRepository, never())
                    .existsByFollowerIdAndFollowedIdAndStatus(any(), any(), any());
        }

        @Test
        @DisplayName("nulls are not mates")
        void nullArgs() {
            assertThat(service.isMate(null, TARGET)).isFalse();
            assertThat(service.isMate(VIEWER, null)).isFalse();
            verify(followRepository, never())
                    .existsByFollowerIdAndFollowedIdAndStatus(any(), any(), any());
        }
    }
}
