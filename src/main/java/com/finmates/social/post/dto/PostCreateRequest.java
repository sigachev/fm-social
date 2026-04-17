package com.finmates.social.post.dto;

import com.finmates.social.post.PostVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class PostCreateRequest {

    @NotBlank(message = "Content must not be blank")
    @Size(max = 1200, message = "Content must not exceed 1200 characters")
    private String content;

    @Size(max = 10, message = "At most 10 media keys allowed")
    private List<@NotBlank @Size(max = 255) String> mediaKeys = new ArrayList<>();

    private PostVisibility visibility = PostVisibility.PUBLIC;
}
