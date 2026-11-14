package com.finmates.social.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.finmates.social.follow.FollowRepository;
import com.finmates.social.follow.FollowsInternalController;
import com.finmates.social.profile.ProfileService;
import java.net.URI;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The X-Internal-Secret gate must hold for every spelling of {@code /api/internal/**} that Spring MVC resolves to the
 * internal controllers. InternalSecretFilter used to compare the raw request URI with startsWith, so
 * {@code /api/%69nternal/follows/mates} reached the controller without the secret. The REAL filter in front of the
 * REAL {@link FollowsInternalController}; only its collaborators are mocked. No database.
 */
class InternalSecretPathBypassTest {

    private static final String GOOD = "test-secret";
    private static final List<String> PREFIXES = List.of(
            "/api/internal", "/api/%69nternal", "/api/%49nternal", "/api/inter%6Eal", "/api/internal;x=1",
            "/api/./internal", "/api/x/../internal", "/api//internal", "//api/internal", "/API/INTERNAL");

    private FollowRepository followRepository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        followRepository = mock(FollowRepository.class);
        when(followRepository.findAllMateIds(1L)).thenReturn(Set.of(2L));
        InternalSecretFilter filter = new InternalSecretFilter();
        ReflectionTestUtils.setField(filter, "internalSharedSecret", GOOD);
        mvc = MockMvcBuilders.standaloneSetup(new FollowsInternalController(followRepository, mock(ProfileService.class)))
                .addFilters(filter).build();
    }

    private int status(String rawPath, String secret) {
        try {
            var req = get(URI.create("http://localhost" + rawPath + "?userId=1"));
            if (secret != null) {
                req = req.header("X-Internal-Secret", secret);
            }
            return mvc.perform(req).andReturn().getResponse().getStatus();
        } catch (Exception rejected) {
            return 400;
        }
    }

    @Test
    void noSpellingReachesTheControllerWithoutTheSecret() {
        for (String prefix : PREFIXES) {
            String path = prefix + "/follows/mates";
            assertThat(status(path, null)).as("no secret: %s", path).isIn(400, 401, 403, 404);
            assertThat(status(path, "wrong")).as("wrong secret: %s", path).isIn(400, 401, 403, 404);
        }
        verifyNoInteractions(followRepository);
    }

    @Test
    void theReportedSpellingAndTheBarePrefixAre401() {
        assertThat(status("/api/internal/follows/mates", null)).isEqualTo(401);
        assertThat(status("/api/%69nternal/follows/mates", null)).isEqualTo(401);
        assertThat(status("/api/internal", null)).isEqualTo(401);
        assertThat(status("/api/%69nternal/", null)).isEqualTo(401);
    }

    @Test
    void theRightSecretStillGetsThrough() {
        assertThat(status("/api/internal/follows/mates", GOOD)).isEqualTo(200);
        assertThat(status("/api/%69nternal/follows/mates", GOOD)).isEqualTo(200);
    }

    @Test
    void otherPathsAreNotGated() {
        assertThat(status("/api/internalx/ping", null)).isNotEqualTo(401);
        assertThat(status("/api/feed", null)).isNotEqualTo(401);
    }
}
