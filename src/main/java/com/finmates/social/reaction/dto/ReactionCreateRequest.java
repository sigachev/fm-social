package com.finmates.social.reaction.dto;

import com.finmates.social.reaction.ReactionTargetType;
import com.finmates.social.reaction.ReactionType;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ReactionCreateRequest {

    @NotNull(message = "targetType is required")
    private ReactionTargetType targetType;

    @NotNull(message = "targetId is required")
    private Long targetId;

    @NotNull(message = "reactionType is required")
    private ReactionType reactionType;
}
