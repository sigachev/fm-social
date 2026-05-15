package com.finmates.social.discussion;

import com.finmates.social.comment.CommentRepository;
import com.finmates.social.comment.MentionRow;
import com.finmates.social.common.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read-only service backing the token detail Discussion panel.
 *
 * <p>v1 surfaces one tab — Mentions — which returns top-level ACTIVE comments
 * cashtagging the current token symbol from surfaces OTHER than the current
 * asset page. PORTFOLIO comments are indexed (cashtag extraction populates
 * them) but not surfaced in v1; turning them on is a single-clause SQL change
 * plus a {@code UserLookupCache.bulkGet(...)} for owner usernames when
 * demand justifies the cross-service surface. See Phase 2.A scope decision
 * in {@code fm-social/CLAUDE.md}.</p>
 */
@Service
@Transactional(readOnly = true)
public class DiscussionService {

    /** Hard cap on page size — matches the convention in CommentController. */
    static final int MAX_PAGE_SIZE = 100;

    /** Minimum effective page size (defensive against 0/negative inputs). */
    static final int MIN_PAGE_SIZE = 1;

    private final CommentRepository commentRepository;

    public DiscussionService(CommentRepository commentRepository) {
        this.commentRepository = commentRepository;
    }

    /**
     * Mentions feed for {@code symbol}, paged with {@code page} + {@code size}.
     * Symbol is normalised to canonical uppercase (defence in depth — the FE
     * already uppercases when constructing the URL, but a typo'd direct curl
     * shouldn't return an empty set just because of casing).
     */
    public PageResponse<MentionResponse> getMentionsForSymbol(String symbol, int page, int size) {
        String normalizedSymbol = symbol == null ? "" : symbol.trim().toUpperCase();
        int clampedSize = Math.min(Math.max(size, MIN_PAGE_SIZE), MAX_PAGE_SIZE);
        int clampedPage = Math.max(page, 0);
        int offset = clampedPage * clampedSize;

        List<MentionRow> rows = commentRepository
                .findMentionsForSymbol(normalizedSymbol, clampedSize, offset);
        long total = commentRepository.countMentionsForSymbol(normalizedSymbol);

        List<MentionResponse> items = rows.stream()
                .map(MentionResponse::from)
                .toList();

        return PageResponse.of(items, clampedPage, clampedSize, total);
    }
}
