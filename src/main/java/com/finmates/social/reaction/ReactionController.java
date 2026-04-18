package com.finmates.social.reaction;

import com.finmates.social.common.security.AuthenticatedUser;
import com.finmates.social.reaction.dto.ReactionAggregateResponse;
import com.finmates.social.reaction.dto.ReactionCreateRequest;
import com.finmates.social.reaction.dto.ReactionToggleResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reactions")
@PreAuthorize("isAuthenticated()")
@Tag(name = "Reactions", description = "Crypto-native reactions: BULLISH, BEARISH, FIRE, DIAMOND_HANDS, REKT")
public class ReactionController {

    private final ReactionService reactionService;
    private final AuthenticatedUser authenticatedUser;

    public ReactionController(ReactionService reactionService, AuthenticatedUser authenticatedUser) {
        this.reactionService = reactionService;
        this.authenticatedUser = authenticatedUser;
    }

    @PostMapping
    @Operation(summary = "Toggle a reaction (adds if absent, removes if present)")
    @ApiResponse(responseCode = "200", description = "Reaction toggled; added=true means added, added=false means removed")
    @ApiResponse(responseCode = "400", description = "Validation error")
    public ReactionToggleResponse toggleReaction(@Valid @RequestBody ReactionCreateRequest req) {
        Long userId = authenticatedUser.currentUserId();
        return reactionService.toggleReaction(userId, req);
    }

    @GetMapping("/post/{postId}")
    @Operation(summary = "Get aggregated reaction counts for a post")
    @ApiResponse(responseCode = "200", description = "Reaction counts by type")
    public ReactionAggregateResponse getPostReactions(@PathVariable Long postId) {
        Long userId = authenticatedUser.currentUserId();
        return reactionService.getAggregates(ReactionTargetType.POST, postId, userId);
    }

    @GetMapping("/comment/{commentId}")
    @Operation(summary = "Get aggregated reaction counts for a comment")
    @ApiResponse(responseCode = "200", description = "Reaction counts by type")
    public ReactionAggregateResponse getCommentReactions(@PathVariable Long commentId) {
        Long userId = authenticatedUser.currentUserId();
        return reactionService.getAggregates(ReactionTargetType.COMMENT, commentId, userId);
    }
}
