package com.finmates.social.follow;

import com.finmates.social.config.InternalSecretFilter;
import com.finmates.social.follow.dto.FollowersSummaryInternalResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice tests for {@link FollowsInternalController}.
 *
 * <p>Standalone MockMvc setup — bypasses Spring Boot's filter auto-discovery (which would otherwise
 * pull in {@code ProfileEnsureFilter} and its transitive dependency on
 * {@code ProfileInitializationService}, both unrelated to this controller). The
 * {@link InternalSecretFilter} is wired explicitly so the auth gate is exercised end-to-end.</p>
 *
 * <p>Cache-hit verification is intentionally omitted from this slice: standalone MockMvc skips
 * the {@code @Cacheable} AOP proxy. Cache wiring is verified by code review of {@code CacheConfig}
 * and the {@code @Cacheable} annotations on the controller; behavior is covered by the staged
 * production deploy + smoke verification documented in cp5-c-design.md §5 step 1.</p>
 */
@ExtendWith(MockitoExtension.class)
class FollowsInternalControllerTest {

    private static final String SECRET = "test-secret-FollowsInternal";
    private static final String HEADER = "X-Internal-Secret";

    @Mock FollowRepository followRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        FollowsInternalController controller = new FollowsInternalController(followRepository);
        InternalSecretFilter filter = new InternalSecretFilter();
        ReflectionTestUtils.setField(filter, "internalSharedSecret", SECRET);

        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        MappingJackson2HttpMessageConverter jsonConverter =
                new MappingJackson2HttpMessageConverter(objectMapper);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(filter)
                .setMessageConverters(jsonConverter)
                .build();
    }

    // ── Auth ────────────────────────────────────────────────────────────────

    @Test
    void mates_missingSecretHeader_returns401() throws Exception {
        mockMvc.perform(get("/api/internal/follows/mates").param("userId", "1"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(followRepository);
    }

    @Test
    void mates_wrongSecret_returns401() throws Exception {
        mockMvc.perform(get("/api/internal/follows/mates")
                        .header(HEADER, "wrong-secret")
                        .param("userId", "1"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(followRepository);
    }

    // ── /mates ──────────────────────────────────────────────────────────────

    @Test
    void mates_happyPath_returnsMateIds() throws Exception {
        when(followRepository.findAllMateIds(42L)).thenReturn(new LinkedHashSet<>(List.of(7L, 11L, 99L)));

        mockMvc.perform(get("/api/internal/follows/mates")
                        .header(HEADER, SECRET)
                        .param("userId", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$").value(org.hamcrest.Matchers.containsInAnyOrder(7, 11, 99)));
    }

    @Test
    void mates_emptyResult_returns200WithEmptyArray() throws Exception {
        when(followRepository.findAllMateIds(42L)).thenReturn(Set.of());

        mockMvc.perform(get("/api/internal/follows/mates")
                        .header(HEADER, SECRET)
                        .param("userId", "42"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void mates_missingUserIdParam_returns400() throws Exception {
        mockMvc.perform(get("/api/internal/follows/mates").header(HEADER, SECRET))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(followRepository);
    }

    @Test
    void mates_nonNumericUserId_returns400() throws Exception {
        mockMvc.perform(get("/api/internal/follows/mates")
                        .header(HEADER, SECRET)
                        .param("userId", "abc"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(followRepository);
    }

    // ── /following ──────────────────────────────────────────────────────────

    @Test
    void following_happyPath() throws Exception {
        when(followRepository.findAllFollowingIds(42L)).thenReturn(new LinkedHashSet<>(List.of(1L, 2L)));

        mockMvc.perform(get("/api/internal/follows/following")
                        .header(HEADER, SECRET)
                        .param("userId", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void following_emptyResult_returns200WithEmptyArray() throws Exception {
        when(followRepository.findAllFollowingIds(42L)).thenReturn(Set.of());

        mockMvc.perform(get("/api/internal/follows/following")
                        .header(HEADER, SECRET)
                        .param("userId", "42"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    // ── /followers ──────────────────────────────────────────────────────────

    @Test
    void followers_happyPath() throws Exception {
        when(followRepository.findAllFollowerIds(42L)).thenReturn(List.of(5L, 6L, 7L));

        mockMvc.perform(get("/api/internal/follows/followers")
                        .header(HEADER, SECRET)
                        .param("userId", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void followers_emptyResult() throws Exception {
        when(followRepository.findAllFollowerIds(42L)).thenReturn(List.of());

        mockMvc.perform(get("/api/internal/follows/followers")
                        .header(HEADER, SECRET)
                        .param("userId", "42"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    // ── /followers-summary ──────────────────────────────────────────────────

    @Test
    void followersSummary_happyPath() throws Exception {
        when(followRepository.countByFollowedIdAndStatus(42L, FollowStatus.ACTIVE)).thenReturn(3L);
        OffsetDateTime now = OffsetDateTime.now();
        when(followRepository.findRecentFollowers(eq(42L), any(Pageable.class)))
                .thenReturn(List.of(
                        view(7L, now.minusHours(1)),
                        view(8L, now.minusHours(5))));

        mockMvc.perform(get("/api/internal/follows/followers-summary")
                        .header(HEADER, SECRET)
                        .param("userId", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.recent.length()").value(2))
                .andExpect(jsonPath("$.recent[0].userId").value(7))
                .andExpect(jsonPath("$.recent[1].userId").value(8));

        verify(followRepository, times(1)).findRecentFollowers(eq(42L), any(Pageable.class));
    }

    @Test
    void followersSummary_emptyResult_returnsZeroCountAndEmptyRecent() throws Exception {
        when(followRepository.countByFollowedIdAndStatus(42L, FollowStatus.ACTIVE)).thenReturn(0L);
        when(followRepository.findRecentFollowers(eq(42L), any(Pageable.class))).thenReturn(List.of());

        mockMvc.perform(get("/api/internal/follows/followers-summary")
                        .header(HEADER, SECRET)
                        .param("userId", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(0))
                .andExpect(jsonPath("$.recent.length()").value(0));
    }

    @Test
    void followersSummary_missingUserId_returns400() throws Exception {
        mockMvc.perform(get("/api/internal/follows/followers-summary").header(HEADER, SECRET))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(followRepository);
    }

    private static FollowRepository.FollowRecentView view(Long followerId, OffsetDateTime createdAt) {
        return new FollowRepository.FollowRecentView() {
            @Override public Long getFollowerId() { return followerId; }
            @Override public OffsetDateTime getCreatedAt() { return createdAt; }
        };
    }

    /**
     * Returns {@link FollowersSummaryInternalResponse} only to keep its symbol referenced
     * for static analysis tooling — the response shape itself is verified via {@code jsonPath}.
     */
    @SuppressWarnings("unused")
    private FollowersSummaryInternalResponse silenceUnusedImport() {
        return new FollowersSummaryInternalResponse(0L, List.of());
    }
}
