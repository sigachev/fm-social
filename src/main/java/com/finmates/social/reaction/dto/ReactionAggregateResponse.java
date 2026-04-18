package com.finmates.social.reaction.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Aggregated reaction counts for a target, plus the authenticated viewer's
 * own reaction type (null if they have not reacted).
 */
public record ReactionAggregateResponse(
        @JsonProperty("BULLISH")       long bullish,
        @JsonProperty("BEARISH")       long bearish,
        @JsonProperty("FIRE")          long fire,
        @JsonProperty("DIAMOND_HANDS") long diamondHands,
        @JsonProperty("REKT")          long rekt,
        long totalCount,
        /** "FIRE", "BULLISH", etc. — the viewer's reaction type, or null. */
        @JsonProperty("myReactionType") String myReactionType
) {
    public static ReactionAggregateResponse empty() {
        return new ReactionAggregateResponse(0, 0, 0, 0, 0, 0, null);
    }
}
