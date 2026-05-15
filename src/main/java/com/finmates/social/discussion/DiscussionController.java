package com.finmates.social.discussion;

import com.finmates.social.common.PageResponse;
import com.finmates.social.common.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Token detail Discussion panel — read endpoints.
 *
 * <p>Class-level default is {@code isAuthenticated()} so any future write
 * endpoints on this controller fail closed. Each read endpoint that should
 * be visible to anonymous viewers overrides explicitly with
 * {@code @PreAuthorize("permitAll()")} — same pattern as
 * {@code PostController.getPostsByCashtag}.</p>
 */
@RestController
@RequestMapping("/api/discussion")
@PreAuthorize("isAuthenticated()")
@Tag(name = "Discussion", description = "Token detail page Discussion panel — Mentions, etc.")
public class DiscussionController {

    private final DiscussionService discussionService;
    private final AuthenticatedUser authenticatedUser;

    public DiscussionController(DiscussionService discussionService,
                                AuthenticatedUser authenticatedUser) {
        this.discussionService = discussionService;
        this.authenticatedUser = authenticatedUser;
    }

    /**
     * Mentions tab for the token detail page. Returns top-level ACTIVE comments
     * cashtagging {@code symbol} from surfaces OTHER than the current asset
     * page itself. v1 scope: ASSET + POST targets. PORTFOLIO targets are
     * indexed but not surfaced.
     *
     * <p>Mixed-auth — anonymous viewers can see Mentions on a public token
     * page. No viewer-personalised filtering (no network scope, no
     * block-list) in v1; block-list filtering is a follow-up that will land
     * across the whole Discussion panel.</p>
     */
    @GetMapping("/token/{symbol}/mentions")
    @PreAuthorize("permitAll()")
    @Operation(summary = "Top-level comments cashtagging this token from other surfaces")
    @ApiResponse(responseCode = "200", description = "Paged MentionResponse list")
    public PageResponse<MentionResponse> getTokenMentions(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return discussionService.getMentionsForSymbol(symbol, page, size);
    }

    /**
     * Aggregate counts for the five Discussion-panel tabs. Single round-trip
     * for the FE to populate tab labels on panel mount. Mixed-auth — anon
     * viewers get {@code yourNetwork: 0} cleanly; the other four counts
     * populate normally.
     *
     * <p>Viewer ID is resolved via {@link AuthenticatedUser#currentUserIdOrNull()}
     * — the centralised helper that returns {@code null} for anonymous
     * requests and for authenticated requests where the JWT lacks the
     * {@code user_id} claim. Pushing the {@code null} into the service
     * (rather than wrapping in an {@code Optional} at the seam) keeps the
     * controller a thin pass-through.</p>
     */
    @GetMapping("/token/{symbol}/counts")
    @PreAuthorize("permitAll()")
    @Operation(summary = "Aggregate counts for Your network / Platform / Comments / Mentions / News tabs")
    @ApiResponse(responseCode = "200", description = "DiscussionCounts with five Long fields (news nullable)")
    public DiscussionCounts getTokenCounts(@PathVariable String symbol) {
        Long viewerUserIdOrNull = authenticatedUser.currentUserIdOrNull();
        return discussionService.getCountsForSymbol(symbol, viewerUserIdOrNull);
    }
}
