package com.finmates.social.discussion;

import com.finmates.social.comment.CommentRepository;
import com.finmates.social.comment.MentionRow;
import com.finmates.social.common.PageResponse;
import com.finmates.social.follow.FollowRepository;
import com.finmates.social.post.PostRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

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
    private final PostRepository postRepository;
    private final FollowRepository followRepository;

    public DiscussionService(CommentRepository commentRepository,
                             PostRepository postRepository,
                             FollowRepository followRepository) {
        this.commentRepository = commentRepository;
        this.postRepository = postRepository;
        this.followRepository = followRepository;
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

    /**
     * Aggregate Discussion-panel tab counts for {@code symbol}.
     *
     * <p>Five fields:</p>
     * <ul>
     *   <li>{@code yourNetwork} — posts cashtagging the symbol from users
     *       the viewer follows. {@code 0} for anonymous viewers (no 401)
     *       and for authenticated viewers whose follow list is empty.</li>
     *   <li>{@code platform} — all posts cashtagging the symbol, regardless
     *       of follow graph.</li>
     *   <li>{@code comments} — total ACTIVE comments on the asset's own
     *       page (top-level + replies, flat). Matches the list endpoint's
     *       {@code Page.totalElements} so the tab label and the list contents
     *       agree on the same number.</li>
     *   <li>{@code mentions} — top-level ACTIVE comments cashtagging the
     *       symbol from other surfaces (Phase 2's count). Deliberately
     *       different shape from {@code comments} above — Mentions is a
     *       feed of threads, the Comments tab is a flat scroll.</li>
     *   <li>{@code news} — always {@code null} in v1; the FE omits the count
     *       from the News tab label when null. Wiring fm-crypto-data's
     *       news catalog as a per-asset count is tracked as a follow-up
     *       in CLAUDE.md.</li>
     * </ul>
     *
     * <p>All count queries run sequentially on the request thread. At current
     * data volume each query is sub-millisecond except the cashtag posts
     * count, which inherits the seq-scan ceiling of {@code posts.content
     * LIKE '%$btc%'} until the planned {@code post_cashtags} migration
     * lands.</p>
     *
     * @param symbol            token symbol — trimmed/uppercased here
     * @param viewerUserIdOrNull authenticated viewer's user_id, or
     *                          {@code null} for anonymous requests
     */
    public DiscussionCounts getCountsForSymbol(String symbol, Long viewerUserIdOrNull) {
        String normalized = symbol == null ? "" : symbol.trim().toUpperCase();
        // Pattern matches the form built by PostService.getPostsByCashtag —
        // lowercased once here so SQL `LOWER(content) LIKE :pattern` collates;
        // the leading `$` anchors to the cashtag prefix and rejects accidental
        // substring matches in body text (e.g. won't match "BTCUSD").
        String cashtagPattern = "%$" + normalized.toLowerCase() + "%";

        long yourNetwork = computeYourNetwork(viewerUserIdOrNull, cashtagPattern);
        long platform = postRepository.countByCashtagGlobal(cashtagPattern);
        // Counts top-level + replies, matching the flat list endpoint.
        // Deliberately different from mentions (which counts threads only) —
        // see CommentRepository.countActiveByAssetSymbol javadoc.
        long comments = commentRepository.countActiveByAssetSymbol(normalized);
        long mentions = commentRepository.countMentionsForSymbol(normalized);
        // News count: see DiscussionCounts javadoc — null is the v1 contract.
        Long news = null;

        return new DiscussionCounts(yourNetwork, platform, comments, mentions, news);
    }

    /**
     * Two short-circuits before the actual count query:
     * <ol>
     *   <li>Anonymous viewer ({@code null}) → no follow context exists, return 0.</li>
     *   <li>Authenticated viewer with empty follow list → no posts can match,
     *       return 0 without a DB count call. Mirrors
     *       {@link com.finmates.social.post.PostService#getPostsByCashtag}'s
     *       empty-set short-circuit.</li>
     * </ol>
     */
    private long computeYourNetwork(Long viewerUserIdOrNull, String cashtagPattern) {
        if (viewerUserIdOrNull == null) {
            return 0L;
        }
        Set<Long> followingIds = followRepository.findAllFollowingIds(viewerUserIdOrNull);
        if (followingIds.isEmpty()) {
            return 0L;
        }
        return postRepository.countByCashtagAndAuthors(cashtagPattern, followingIds);
    }
}
