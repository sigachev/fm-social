package com.finmates.social.feed;

import com.finmates.social.post.dto.PostResponse;

import java.util.List;

/**
 * Paginated feed response with cursor-based pagination.
 */
public record FeedResponse(
        List<PostResponse> posts,
        Long nextCursor,
        boolean hasMore
) {}
