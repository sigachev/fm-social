package com.finmates.social.access;

import com.finmates.social.block.BlockRepository;
import com.finmates.social.follow.FollowRepository;
import com.finmates.social.post.Post;
import com.finmates.social.post.PostStatus;
import com.finmates.social.post.PostVisibility;
import com.finmates.social.profile.PortfolioVisibility;
import com.finmates.social.profile.Profile;
import com.finmates.social.profile.ProfileRepository;
import com.finmates.social.profile.ProfileVisibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Author 10. Viewers: 20 follows 10 (one click), 30 is a mate (10 follows back), 40 a stranger, 50 blocked, null signed out.
 */
class AudienceServiceTest {

    FollowRepository follows = mock(FollowRepository.class);
    BlockRepository blocks = mock(BlockRepository.class);
    ProfileRepository profiles = mock(ProfileRepository.class);
    AudienceService audience = new AudienceService(follows, blocks, profiles);
    Profile author = new Profile();

    @BeforeEach
    void setUp() {
        author.setUserId(10L);
        when(profiles.findById(10L)).thenReturn(Optional.of(author));
        when(follows.findAllFollowingIds(20L)).thenReturn(Set.of(10L));
        when(follows.findAllFollowingIds(30L)).thenReturn(Set.of(10L));
        when(follows.findAllFollowingIds(anyLong())).thenAnswer(i -> switch (((Long) i.getArgument(0)).intValue()) {
            case 20, 30 -> Set.of(10L);
            default -> Set.of();
        });
        when(follows.findAllMateIds(anyLong())).thenAnswer(i -> ((Long) i.getArgument(0)) == 30L ? Set.of(10L) : Set.of());
        when(blocks.existsBlockInEitherDirection(anyLong(), anyLong())).thenAnswer(i -> ((Long) i.getArgument(0)) == 50L);
    }

    Post post(PostVisibility v) {
        Post p = new Post();
        p.setAuthorId(10L);
        p.setVisibility(v);
        p.setStatus(PostStatus.ACTIVE);
        return p;
    }

    boolean sees(Long viewer, Post p) {
        return audience.viewer(viewer).canSee(p);
    }

    @Test
    void everyonePostOnAPublicAccount() {
        Post p = post(PostVisibility.PUBLIC);
        assertThat(sees(null, p)).isTrue();
        assertThat(sees(40L, p)).isTrue();
        assertThat(sees(50L, p)).as("blocked").isFalse();
    }

    @Test
    void matesPostIsForPeopleTheAuthorFollowsBack() {
        Post p = post(PostVisibility.MATES);
        assertThat(sees(10L, p)).as("author").isTrue();
        assertThat(sees(30L, p)).as("mate").isTrue();
        assertThat(sees(20L, p)).as("one-click follower").isFalse();
        assertThat(sees(40L, p)).isFalse();
        assertThat(sees(null, p)).isFalse();
    }

    @Test
    void onlyMeIsTheAuthor() {
        Post p = post(PostVisibility.PRIVATE);
        assertThat(sees(10L, p)).isTrue();
        assertThat(sees(30L, p)).isFalse();
    }

    @Test
    void privateAccountMeansApprovedFollowersForEveryonePosts() {
        author.setPrivate(true);
        Post p = post(PostVisibility.PUBLIC);
        assertThat(sees(20L, p)).as("approved follower").isTrue();
        assertThat(sees(40L, p)).isFalse();
        assertThat(sees(null, p)).isFalse();
    }

    @Test
    void theAccountMustBeVisibleToo() {
        author.setProfileVisibility(ProfileVisibility.MATES);
        assertThat(sees(20L, post(PostVisibility.PUBLIC))).isFalse();
        assertThat(sees(30L, post(PostVisibility.PUBLIC))).isTrue();
        author.setProfileVisibility(ProfileVisibility.PRIVATE);
        assertThat(sees(30L, post(PostVisibility.PUBLIC))).isFalse();
        assertThat(sees(10L, post(PostVisibility.PUBLIC))).isTrue();
    }

    @Test
    void removedIsGoneAndHiddenIsTheAuthorsOnly() {
        Post p = post(PostVisibility.PUBLIC);
        p.setStatus(PostStatus.REMOVED);
        assertThat(sees(10L, p)).isFalse();
        p.setStatus(PostStatus.UNDER_REVIEW);
        assertThat(sees(10L, p)).isTrue();
        assertThat(sees(40L, p)).isFalse();
    }

    @Test
    void portfolioFollowsItsOwnLevel() {
        author.setPortfolioVisibility(PortfolioVisibility.MATES);
        assertThat(audience.viewer(30L).canSeePortfolio(10L)).isTrue();
        assertThat(audience.viewer(20L).canSeePortfolio(10L)).isFalse();
        assertThat(audience.viewer(20L).canSeeAccount(10L)).isTrue();
    }

    @Test
    void aViewerLooksThingsUpOnce() {
        var v = audience.viewer(30L);
        for (int i = 0; i < 5; i++) v.canSee(post(PostVisibility.MATES));
        verify(follows, times(1)).findAllMateIds(30L);
        verify(profiles, times(1)).findById(10L);
    }
}
