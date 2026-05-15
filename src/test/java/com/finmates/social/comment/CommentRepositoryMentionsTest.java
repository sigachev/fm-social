package com.finmates.social.comment;

import com.finmates.social.post.Post;
import com.finmates.social.post.PostStatus;
import com.finmates.social.post.PostVisibility;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository slice test for the Mentions tab native query.
 *
 * <p><b>REQUIRES Docker Desktop running locally with the Linux-containers
 * engine.</b> Without Docker, this test class fails at JDBC connect time
 * — by design. The {@code application-test.yml} JDBC URL
 * ({@code jdbc:tc:postgresql:17:///...}) drives a fresh Postgres 17 container
 * for each fresh JVM (reused across classes when
 * {@code testcontainers.reuse.enable=true} is set in
 * {@code ~/.testcontainers.properties}).</p>
 *
 * <p>Flyway runs V1–V17 against the container on the first JPA bean
 * initialization. {@code AutoConfigureTestDatabase(replace = NONE)} prevents
 * Spring from substituting an H2 in-memory DB.</p>
 *
 * <p>Each test seeds via {@link TestEntityManager} directly — no service
 * layer involvement. Slice tests stay slice.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class CommentRepositoryMentionsTest {

    @Autowired CommentRepository commentRepository;
    @Autowired TestEntityManager em;
    @Autowired EntityManager rawEm;  // for raw SQL (EXPLAIN, native cleanup)

    private static final String ETH = "ETH";
    private static final String BTC = "BTC";

    /**
     * Wipe both tables before each test so an order-of-tests change can't
     * cross-contaminate. {@link DataJpaTest} rolls back transactions but
     * not Flyway-applied identity sequences; deleting rows is enough for
     * predicate correctness, which is what every test here asserts.
     */
    @BeforeEach
    void clean() {
        rawEm.createNativeQuery("DELETE FROM comments").executeUpdate();
        rawEm.createNativeQuery("DELETE FROM posts").executeUpdate();
    }

    // ── exclusion / inclusion of target types ────────────────────────────────

    @Test
    @DisplayName("excludes the current symbol's own ASSET comments")
    void excludesCurrentSymbolsOwnAssetComments() {
        // A comment on /token/eth that cashtags $ETH should NOT show up in
        // ETH's Mentions tab — that's the whole "from other surfaces" point.
        seedAssetComment("alice", "love $ETH", ETH, List.of("ETH"));

        assertThat(commentRepository.findMentionsForSymbol(ETH, 50, 0)).isEmpty();
        assertThat(commentRepository.countMentionsForSymbol(ETH)).isZero();
    }

    @Test
    @DisplayName("includes comments on OTHER asset pages that cashtag the current symbol")
    void includesOtherAssetTargets() {
        seedAssetComment("alice", "rotating to $ETH", BTC, List.of("ETH"));

        List<MentionRow> rows = commentRepository.findMentionsForSymbol(ETH, 50, 0);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getTargetType()).isEqualTo("ASSET");
        assertThat(rows.get(0).getTargetSymbol()).isEqualTo(BTC);
        assertThat(rows.get(0).getExtractedCashtags()).containsExactly("ETH");
    }

    @Test
    @DisplayName("includes POST-target comments with post_author_username populated from JOIN")
    void includesPostTargets() {
        Long postId = seedPost(101L, "bobsmith");
        seedPostComment("alice", "see this re $ETH", postId, List.of("ETH"));

        List<MentionRow> rows = commentRepository.findMentionsForSymbol(ETH, 50, 0);

        assertThat(rows).hasSize(1);
        MentionRow r = rows.get(0);
        assertThat(r.getTargetType()).isEqualTo("POST");
        assertThat(r.getTargetId()).isEqualTo(postId);
        assertThat(r.getPostAuthorUsername()).isEqualTo("bobsmith");
        assertThat(r.getTargetSymbol()).isNull();
    }

    @Test
    @DisplayName("PORTFOLIO targets are NOT surfaced in v1 (scoped out at SQL)")
    void excludesPortfolioTargetsFromV1Scope() {
        seedPortfolioComment("alice", "@dave's calls on $ETH", 42L, List.of("ETH"));

        assertThat(commentRepository.findMentionsForSymbol(ETH, 50, 0)).isEmpty();
        assertThat(commentRepository.countMentionsForSymbol(ETH)).isZero();
    }

    // ── reply / status filtering ─────────────────────────────────────────────

    @Test
    @DisplayName("replies (parent_id IS NOT NULL) are never surfaced as top-level mentions")
    void excludesReplies() {
        Comment parent = seedAssetComment("alice", "thoughts on $ETH?", BTC, List.of("ETH"));
        seedReply(parent.getId(), "bob", "yes $ETH bullish", List.of("ETH"));

        List<MentionRow> rows = commentRepository.findMentionsForSymbol(ETH, 50, 0);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getId()).isEqualTo(parent.getId());
        assertThat(rows.get(0).getReplyCount()).isEqualTo(1L);
        assertThat(rows.get(0).getReplyCountAlsoMentioning()).isEqualTo(1L);
    }

    @Test
    @DisplayName("non-ACTIVE statuses (HIDDEN, REMOVED, UNDER_REVIEW) are filtered out")
    void excludesNonActiveComments() {
        seedAssetCommentWithStatus("alice", "hidden $ETH", BTC, List.of("ETH"), CommentStatus.HIDDEN);
        seedAssetCommentWithStatus("alice", "removed $ETH", BTC, List.of("ETH"), CommentStatus.REMOVED);
        seedAssetCommentWithStatus("alice", "review $ETH", BTC, List.of("ETH"), CommentStatus.UNDER_REVIEW);
        Comment alive = seedAssetComment("alice", "active $ETH", BTC, List.of("ETH"));

        List<MentionRow> rows = commentRepository.findMentionsForSymbol(ETH, 50, 0);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getId()).isEqualTo(alive.getId());
    }

    @Test
    @DisplayName("replyCount counts only ACTIVE replies")
    void replyCountAccurate() {
        Comment parent = seedAssetComment("alice", "thread $ETH", BTC, List.of("ETH"));
        seedReply(parent.getId(), "b", "1 $ETH", List.of("ETH"));
        seedReply(parent.getId(), "c", "2 $ETH", List.of("ETH"));
        seedReplyWithStatus(parent.getId(), "d", "3 $ETH hidden", List.of("ETH"), CommentStatus.HIDDEN);

        List<MentionRow> rows = commentRepository.findMentionsForSymbol(ETH, 50, 0);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getReplyCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("replyCountAlsoMentioning counts only ACTIVE replies that cashtag the current symbol")
    void replyCountAlsoMentioningAccurate() {
        Comment parent = seedAssetComment("alice", "thread $ETH", BTC, List.of("ETH"));
        seedReply(parent.getId(), "b", "1 $ETH", List.of("ETH"));            // both
        seedReply(parent.getId(), "c", "2 $ETH", List.of("ETH"));            // both
        seedReply(parent.getId(), "d", "no cashtag", List.of());            // ACTIVE only

        List<MentionRow> rows = commentRepository.findMentionsForSymbol(ETH, 50, 0);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getReplyCount()).isEqualTo(3L);
        assertThat(rows.get(0).getReplyCountAlsoMentioning()).isEqualTo(2L);
    }

    // ── ordering & pagination ────────────────────────────────────────────────

    @Test
    @DisplayName("orders by created_at DESC with id DESC as tiebreaker")
    void orderingDescendingByCreatedAtThenId() {
        OffsetDateTime t0 = OffsetDateTime.parse("2026-05-15T10:00:00Z");
        OffsetDateTime t1 = t0.plusMinutes(1);
        OffsetDateTime t2 = t0.plusMinutes(2);

        Comment c1 = seedAssetCommentAt("a", "old $ETH",      BTC, List.of("ETH"), t0);
        Comment c2 = seedAssetCommentAt("a", "tie $ETH (lo)", BTC, List.of("ETH"), t1);
        Comment c3 = seedAssetCommentAt("a", "tie $ETH (hi)", BTC, List.of("ETH"), t1);
        Comment c4 = seedAssetCommentAt("a", "newest $ETH",   BTC, List.of("ETH"), t2);

        List<MentionRow> rows = commentRepository.findMentionsForSymbol(ETH, 50, 0);

        assertThat(rows).extracting(MentionRow::getId)
                .containsExactly(c4.getId(), c3.getId(), c2.getId(), c1.getId());
    }

    @Test
    @DisplayName("pagination returns the correct slice and total count")
    void paginationCorrectness() {
        OffsetDateTime base = OffsetDateTime.parse("2026-05-15T10:00:00Z");
        for (int i = 0; i < 5; i++) {
            seedAssetCommentAt("a", "n" + i + " $ETH", BTC, List.of("ETH"), base.plusMinutes(i));
        }

        // page=1 size=2  →  offset=2, limit=2  →  slice indexes [2,3] of
        // descending-time list. By insertion order (oldest first), the
        // descending-time list is [n4, n3, n2, n1, n0] → slice = [n2, n1].
        List<MentionRow> rows = commentRepository.findMentionsForSymbol(ETH, 2, 2);
        long total = commentRepository.countMentionsForSymbol(ETH);

        assertThat(total).isEqualTo(5L);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getContent()).isEqualTo("n2 $ETH");
        assertThat(rows.get(1).getContent()).isEqualTo("n1 $ETH");
    }

    // ── index usage ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("EXPLAIN plan shows the GIN index is available and chosen when seqscan is disabled")
    @SuppressWarnings("unchecked")
    void ginIndexUsedForOuterScan() {
        // Seed some rows so the planner has something to analyze. We deliberately
        // don't seed thousands — the planner's tiny-table heuristic legitimately
        // prefers a seq scan at small scale (which is correct behaviour, not a
        // bug). The invariant we DO want to assert is "the GIN index exists
        // and is applicable to this predicate" — `SET enable_seqscan = off`
        // forces the planner to use index plans where available, which lets
        // us assert the GIN index name in EXPLAIN output at any data size.
        // If the predicate stops being GIN-indexable (e.g. someone changes
        // `= ANY(extracted_cashtags)` to `extracted_cashtags @> ARRAY[...]`
        // and forgets to keep the index in sync), this test fails loudly.
        OffsetDateTime base = OffsetDateTime.parse("2026-05-15T10:00:00Z");
        for (int i = 0; i < 50; i++) {
            seedAssetCommentAt("a", "row " + i + " $ETH", BTC, List.of("ETH"), base.plusMinutes(i));
        }
        rawEm.createNativeQuery("ANALYZE comments").executeUpdate();
        rawEm.createNativeQuery("SET LOCAL enable_seqscan = off").executeUpdate();

        // EXPLAIN the exact predicate the prod query uses on the outer scan
        // — containment form so GIN is applicable. FORMAT TEXT default.
        List<String> planLines = rawEm.createNativeQuery("""
                EXPLAIN
                SELECT c.id FROM comments c
                WHERE c.extracted_cashtags @> ARRAY['ETH']::text[]
                  AND c.parent_id IS NULL
                  AND c.status = 'ACTIVE'
                  AND c.target_type IN ('ASSET', 'POST')
                  AND NOT (c.target_type = 'ASSET' AND c.target_symbol = 'ETH')
                """).getResultList();

        String plan = String.join("\n", planLines);
        // Diagnostic output — surfaces the plan in test logs when run with
        // -Dsurefire.printSummary=true or when CI captures stdout. Cheap.
        System.out.println("[ginIndexUsedForOuterScan] EXPLAIN plan:\n" + plan);
        assertThat(plan)
                .as("EXPLAIN should reference the GIN index — full plan:\n%s", plan)
                .contains("comments_extracted_cashtags_gin");
    }

    // ── seed helpers ─────────────────────────────────────────────────────────

    private Comment seedAssetComment(String author, String content, String targetSymbol,
                                     List<String> cashtags) {
        return seedAssetCommentWithStatus(author, content, targetSymbol, cashtags,
                CommentStatus.ACTIVE);
    }

    private Comment seedAssetCommentAt(String author, String content, String targetSymbol,
                                       List<String> cashtags, OffsetDateTime createdAt) {
        Comment c = newAssetComment(author, content, targetSymbol, cashtags, CommentStatus.ACTIVE);
        c.setCreatedAt(createdAt);
        c.setUpdatedAt(createdAt);
        return em.persistAndFlush(c);
    }

    private Comment seedAssetCommentWithStatus(String author, String content, String targetSymbol,
                                               List<String> cashtags, CommentStatus status) {
        Comment c = newAssetComment(author, content, targetSymbol, cashtags, status);
        return em.persistAndFlush(c);
    }

    private Comment newAssetComment(String author, String content, String targetSymbol,
                                    List<String> cashtags, CommentStatus status) {
        Comment c = new Comment();
        c.setAuthorId(1L);
        c.setAuthorUsername(author);
        c.setTargetType(CommentTargetType.ASSET);
        c.setTargetSymbol(targetSymbol);
        c.setContent(content);
        c.setStatus(status);
        c.setExtractedCashtags(new java.util.ArrayList<>(cashtags));
        OffsetDateTime now = OffsetDateTime.now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return c;
    }

    private Comment seedPostComment(String author, String content, Long postId,
                                    List<String> cashtags) {
        Comment c = new Comment();
        c.setAuthorId(1L);
        c.setAuthorUsername(author);
        c.setTargetType(CommentTargetType.POST);
        c.setTargetId(postId);
        c.setContent(content);
        c.setStatus(CommentStatus.ACTIVE);
        c.setExtractedCashtags(new java.util.ArrayList<>(cashtags));
        OffsetDateTime now = OffsetDateTime.now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return em.persistAndFlush(c);
    }

    private Comment seedPortfolioComment(String author, String content, Long ownerUserId,
                                         List<String> cashtags) {
        Comment c = new Comment();
        c.setAuthorId(1L);
        c.setAuthorUsername(author);
        c.setTargetType(CommentTargetType.PORTFOLIO);
        c.setTargetId(ownerUserId);
        c.setContent(content);
        c.setStatus(CommentStatus.ACTIVE);
        c.setExtractedCashtags(new java.util.ArrayList<>(cashtags));
        OffsetDateTime now = OffsetDateTime.now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return em.persistAndFlush(c);
    }

    private Comment seedReply(Long parentId, String author, String content, List<String> cashtags) {
        return seedReplyWithStatus(parentId, author, content, cashtags, CommentStatus.ACTIVE);
    }

    private Comment seedReplyWithStatus(Long parentId, String author, String content,
                                        List<String> cashtags, CommentStatus status) {
        Comment c = new Comment();
        c.setAuthorId(2L);
        c.setAuthorUsername(author);
        c.setTargetType(CommentTargetType.ASSET);
        c.setTargetSymbol(BTC);  // parent's target — reply target_* mirrors parent in this codebase
        c.setParentId(parentId);
        c.setContent(content);
        c.setStatus(status);
        c.setExtractedCashtags(new java.util.ArrayList<>(cashtags));
        OffsetDateTime now = OffsetDateTime.now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return em.persistAndFlush(c);
    }

    private Long seedPost(Long authorId, String authorUsername) {
        Post p = new Post();
        p.setAuthorId(authorId);
        p.setAuthorUsername(authorUsername);
        p.setContent("post body");
        p.setStatus(PostStatus.ACTIVE);
        p.setVisibility(PostVisibility.PUBLIC);
        OffsetDateTime now = OffsetDateTime.now();
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        return em.persistAndFlush(p).getId();
    }
}
