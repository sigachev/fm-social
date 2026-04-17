package com.finmates.social.post.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class PostUpdateRequest {

    @NotBlank(message = "Content must not be blank")
    @Size(max = 1200, message = "Content must not exceed 1200 characters")
    private String content;
}
