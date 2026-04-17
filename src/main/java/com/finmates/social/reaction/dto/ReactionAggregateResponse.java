package com.finmates.social.reaction.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ReactionAggregateResponse(
        @JsonProperty("BULLISH")       long bullish,
        @JsonProperty("BEARISH")       long bearish,
        @JsonProperty("FIRE")          long fire,
        @JsonProperty("DIAMOND_HANDS") long diamondHands,
        @JsonProperty("REKT")          long rekt,
        long totalCount
) {
    public static ReactionAggregateResponse empty() {
        return new ReactionAggregateResponse(0, 0, 0, 0, 0, 0);
    }
}
