package com.finmates.social.comment.dto;

import com.finmates.social.comment.CommentTargetType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class CommentCreateRequest {

    @NotNull(message = "targetType is required")
    private CommentTargetType targetType;

    private Long targetId;

    @Size(max = 20)
    private String targetSymbol;

    private Long parentId;

    @NotBlank(message = "Content must not be blank")
    @Size(max = 500, message = "Content must not exceed 500 characters")
    private String content;

    @AssertTrue(message = "Exactly one of targetId or targetSymbol must be set, matching targetType")
    public boolean isTargetValid() {
        if (targetType == null) return false;
        return switch (targetType) {
            case POST, PORTFOLIO -> targetId != null && targetSymbol == null;
            case ASSET -> targetSymbol != null && targetId == null;
        };
    }
}
