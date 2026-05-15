package com.finmates.social.discussion;

import com.finmates.social.common.PageResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC-slice test for {@link DiscussionController}. Verifies:
 *
 * <ul>
 *   <li>The controller dispatches to {@link DiscussionService} with the
 *       expected (symbol, page, size) arguments — including param defaults.</li>
 *   <li>The JSON response shape matches the {@link PageResponse} contract
 *       that the FE relies on.</li>
 *   <li>The {@code @PreAuthorize("permitAll()")} method annotation is present
 *       at the bytecode level (verified by reflection — see
 *       {@code permitAllAnnotationPresent}).</li>
 * </ul>
 *
 * <p>Security filters are disabled here ({@code addFilters = false}) so the
 * URL-level auth chain doesn't gate on a real JWT. The end-to-end permitAll
 * behavior is validated by the curl smoke test against the running service —
 * SecurityConfig.PUBLIC_PATHS already lists the Mentions path as anonymously
 * accessible, mirroring the existing /api/posts/by-cashtag pattern.</p>
 */
@WebMvcTest(
        controllers = DiscussionController.class,
        excludeAutoConfiguration = OAuth2ResourceServerAutoConfiguration.class
)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = {
        // Defensive overrides — these properties are only consumed by
        // SecurityConfig / OAuth2 autoconfig, both excluded above. Setting
        // them here guarantees the slice context bootstraps even if a
        // future change re-introduces a transitive dependency on them.
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost/no-op-issuer",
        "finmates.internal.shared-secret=test-secret"
})
class DiscussionControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean DiscussionService discussionService;

    // @WebMvcTest picks up @Component-annotated Filter classes (ProfileEnsureFilter,
    // InternalSecretFilter) and their @Configuration container (SecurityConfig).
    // None of them run with addFilters=false, but the beans still have to construct,
    // which means satisfying their transitive deps with mocks. Auth-side beans
    // touched by the security config are mocked here regardless of whether the
    // test actually exercises them.
    @MockBean com.finmates.social.profile.ProfileInitializationService profileInitializationService;
    @MockBean com.finmates.social.config.security.JwtAuthConverter jwtAuthConverter;

    @Test
    @DisplayName("returns 200 with empty PageResponse JSON for a valid symbol")
    void returns200OnValidSymbol() throws Exception {
        when(discussionService.getMentionsForSymbol(anyString(), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(List.of(), 0, 20, 0L));

        mockMvc.perform(get("/api/discussion/token/ETH/mentions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    @DisplayName("absent query params default page=0, size=20")
    void defaultsPageToZeroAndSizeToTwenty() throws Exception {
        when(discussionService.getMentionsForSymbol(anyString(), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(List.of(), 0, 20, 0L));

        mockMvc.perform(get("/api/discussion/token/ETH/mentions"))
                .andExpect(status().isOk());

        verify(discussionService).getMentionsForSymbol(eq("ETH"), eq(0), eq(20));
    }

    @Test
    @DisplayName("page and size query params are parsed and forwarded")
    void parsesPageAndSizeParams() throws Exception {
        when(discussionService.getMentionsForSymbol(anyString(), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(List.of(), 3, 50, 0L));

        mockMvc.perform(get("/api/discussion/token/SOL/mentions")
                        .param("page", "3")
                        .param("size", "50"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> symbolCap = ArgumentCaptor.forClass(String.class);
        verify(discussionService).getMentionsForSymbol(symbolCap.capture(), eq(3), eq(50));
        assertThat(symbolCap.getValue()).isEqualTo("SOL");
    }

    @Test
    @DisplayName("response JSON exposes the full PageResponse envelope")
    void responseShapeMatchesPageResponseContract() throws Exception {
        MentionResponse m = new MentionResponse(
                7L, "alice", "mentions $ETH", List.of("ETH"),
                "POST", null, 42L, "bobsmith",
                3L, 1L,
                OffsetDateTime.parse("2026-05-15T01:00:00Z"),
                OffsetDateTime.parse("2026-05-15T01:00:00Z")
        );
        when(discussionService.getMentionsForSymbol(anyString(), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(List.of(m), 0, 20, 1L));

        mockMvc.perform(get("/api/discussion/token/ETH/mentions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(7))
                .andExpect(jsonPath("$.content[0].targetType").value("POST"))
                .andExpect(jsonPath("$.content[0].targetId").value(42))
                .andExpect(jsonPath("$.content[0].targetAuthorUsername").value("bobsmith"))
                .andExpect(jsonPath("$.content[0].targetSymbol").doesNotExist())
                .andExpect(jsonPath("$.content[0].replyCount").value(3))
                .andExpect(jsonPath("$.content[0].replyCountAlsoMentioning").value(1))
                .andExpect(jsonPath("$.content[0].extractedCashtags[0]").value("ETH"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    @DisplayName("@PreAuthorize(\"permitAll()\") annotation is present on the handler")
    void permitAllAnnotationPresent() throws NoSuchMethodException {
        // Reflection check — confirms the source-code annotation exists at the
        // bytecode level. The end-to-end permitAll behavior (anonymous request
        // returns 200 against a running service) is validated by the curl
        // smoke test, not by this @WebMvcTest slice.
        var method = DiscussionController.class.getMethod(
                "getTokenMentions", String.class, int.class, int.class);
        var preAuthorize = method.getAnnotation(
                org.springframework.security.access.prepost.PreAuthorize.class);
        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("permitAll()");
    }
}
