package com.finmates.social.discussion;

import com.finmates.social.comment.CommentRepository;
import com.finmates.social.comment.MentionRow;
import com.finmates.social.common.PageResponse;
import com.finmates.social.follow.FollowRepository;
import com.finmates.social.post.PostRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito tests for {@link DiscussionService} — no Spring context.
 * Validates symbol normalisation, page/size clamping, empty-result mapping,
 * and the ASSET/POST row-shape branching in {@link MentionResponse#from}.
 *
 * <p>Repository slice tests for the underlying SQL live in
 * {@code CommentRepositoryMentionsTest} (Testcontainers).</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT) // stubRow() stubs every getter; per-test branches use a subset
class DiscussionServiceTest {

    @Mock CommentRepository commentRepository;
    @Mock PostRepository postRepository;
    @Mock FollowRepository followRepository;

    @InjectMocks DiscussionService service;

    // ── normalisation & clamping ──────────────────────────────────────────────

    @Test
    @DisplayName("symbol is trimmed and uppercased before hitting the repository")
    void normalizesSymbolToUppercase() {
        when(commentRepository.findMentionsForSymbol(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(commentRepository.countMentionsForSymbol(anyString())).thenReturn(0L);

        service.getMentionsForSymbol("  eth  ", 0, 20);

        ArgumentCaptor<String> symbolCap = ArgumentCaptor.forClass(String.class);
        verify(commentRepository).findMentionsForSymbol(symbolCap.capture(), eq(20), eq(0));
        verify(commentRepository).countMentionsForSymbol(symbolCap.getValue());
        assertThat(symbolCap.getValue()).isEqualTo("ETH");
    }

    @Test
    @DisplayName("size > MAX_PAGE_SIZE is clamped to 100")
    void clampsSizeToMaximum() {
        when(commentRepository.findMentionsForSymbol(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(commentRepository.countMentionsForSymbol(anyString())).thenReturn(0L);

        service.getMentionsForSymbol("ETH", 0, 500);

        verify(commentRepository).findMentionsForSymbol(eq("ETH"), eq(100), eq(0));
    }

    @Test
    @DisplayName("size <= 0 is clamped up to MIN_PAGE_SIZE (=1)")
    void clampsSizeToMinimum() {
        when(commentRepository.findMentionsForSymbol(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(commentRepository.countMentionsForSymbol(anyString())).thenReturn(0L);

        service.getMentionsForSymbol("ETH", 0, 0);

        verify(commentRepository).findMentionsForSymbol(eq("ETH"), eq(1), eq(0));
    }

    @Test
    @DisplayName("negative page is clamped to 0 (offset = 0)")
    void clampsPageToZero() {
        when(commentRepository.findMentionsForSymbol(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(commentRepository.countMentionsForSymbol(anyString())).thenReturn(0L);

        service.getMentionsForSymbol("ETH", -5, 20);

        verify(commentRepository).findMentionsForSymbol(eq("ETH"), eq(20), eq(0));
    }

    // ── empty / non-empty result mapping ─────────────────────────────────────

    @Test
    @DisplayName("empty repository result returns an empty PageResponse with total=0, never null")
    void emptyResultReturnsEmptyPageResponse() {
        when(commentRepository.findMentionsForSymbol(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(commentRepository.countMentionsForSymbol(anyString())).thenReturn(0L);

        PageResponse<MentionResponse> page = service.getMentionsForSymbol("ETH", 0, 20);

        assertThat(page).isNotNull();
        assertThat(page.content()).isNotNull().isEmpty();
        assertThat(page.totalElements()).isZero();
        assertThat(page.totalPages()).isZero();
        assertThat(page.last()).isTrue();
    }

    @Test
    @DisplayName("ASSET row maps with targetSymbol set; targetId and targetAuthorUsername null")
    void mapsRowToResponseAsset() {
        MentionRow row = stubRow("ASSET", "BTC", 999L, "@bobsmith", 7L, 2L);
        when(commentRepository.findMentionsForSymbol(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(row));
        when(commentRepository.countMentionsForSymbol(anyString())).thenReturn(1L);

        PageResponse<MentionResponse> page = service.getMentionsForSymbol("ETH", 0, 20);

        assertThat(page.content()).hasSize(1);
        MentionResponse m = page.content().get(0);
        assertThat(m.targetType()).isEqualTo("ASSET");
        assertThat(m.targetSymbol()).isEqualTo("BTC");
        assertThat(m.targetId()).isNull();
        assertThat(m.targetAuthorUsername()).isNull();
        assertThat(m.replyCount()).isEqualTo(7L);
        assertThat(m.replyCountAlsoMentioning()).isEqualTo(2L);
        assertThat(m.extractedCashtags()).containsExactly("ETH");
    }

    @Test
    @DisplayName("POST row maps with targetId and targetAuthorUsername set; targetSymbol null")
    void mapsRowToResponsePost() {
        MentionRow row = stubRow("POST", null, 42L, "bobsmith", 3L, 1L);
        when(commentRepository.findMentionsForSymbol(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(row));
        when(commentRepository.countMentionsForSymbol(anyString())).thenReturn(1L);

        PageResponse<MentionResponse> page = service.getMentionsForSymbol("ETH", 0, 20);

        MentionResponse m = page.content().get(0);
        assertThat(m.targetType()).isEqualTo("POST");
        assertThat(m.targetSymbol()).isNull();
        assertThat(m.targetId()).isEqualTo(42L);
        assertThat(m.targetAuthorUsername()).isEqualTo("bobsmith");
        assertThat(m.replyCount()).isEqualTo(3L);
        assertThat(m.replyCountAlsoMentioning()).isEqualTo(1L);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Build a fake {@link MentionRow} using a Mockito mock. Simpler than a
     * dedicated test impl class — projection interfaces are getter-only.
     */
    private static MentionRow stubRow(String targetType,
                                      String targetSymbol,
                                      Long targetId,
                                      String postAuthorUsername,
                                      long replyCount,
                                      long replyCountAlsoMentioning) {
        MentionRow row = org.mockito.Mockito.mock(MentionRow.class);
        when(row.getId()).thenReturn(1L);
        when(row.getAuthorUsername()).thenReturn("alice");
        when(row.getContent()).thenReturn("mentions $ETH");
        when(row.getTargetType()).thenReturn(targetType);
        when(row.getTargetSymbol()).thenReturn(targetSymbol);
        when(row.getTargetId()).thenReturn(targetId);
        when(row.getPostAuthorUsername()).thenReturn(postAuthorUsername);
        when(row.getExtractedCashtags()).thenReturn(new String[]{"ETH"});
        when(row.getReplyCount()).thenReturn(replyCount);
        when(row.getReplyCountAlsoMentioning()).thenReturn(replyCountAlsoMentioning);
        // MentionRow timestamps are Instant (Hibernate native-projection default
        // for TIMESTAMPTZ). Tests for OffsetDateTime conversion live in the
        // response-shape assertions on the actual record.
        Instant now = Instant.now();
        when(row.getCreatedAt()).thenReturn(now);
        when(row.getUpdatedAt()).thenReturn(now);
        return row;
    }

    // ── getCountsForSymbol ────────────────────────────────────────────────────

    @Test
    @DisplayName("anonymous viewer → yourNetwork is 0, no follow lookup, other counts still computed")
    void getCountsForSymbol_anonViewer_yourNetworkIsZero() {
        when(postRepository.countByCashtagGlobal(anyString())).thenReturn(7L);
        when(commentRepository.countActiveByAssetSymbol("ETH")).thenReturn(3L);
        when(commentRepository.countMentionsForSymbol("ETH")).thenReturn(2L);

        DiscussionCounts counts = service.getCountsForSymbol("ETH", null);

        assertThat(counts.yourNetwork()).isZero();
        assertThat(counts.platform()).isEqualTo(7L);
        assertThat(counts.comments()).isEqualTo(3L);
        assertThat(counts.mentions()).isEqualTo(2L);
        assertThat(counts.news()).isNull();
        // Critical: no follow lookup and no by-authors count for anon.
        verify(followRepository, org.mockito.Mockito.never()).findAllFollowingIds(any());
        verify(postRepository, org.mockito.Mockito.never()).countByCashtagAndAuthors(anyString(), any());
    }

    @Test
    @DisplayName("authed viewer with empty follow list → yourNetwork is 0, no by-authors count call")
    void getCountsForSymbol_authViewerWithEmptyFollows_yourNetworkIsZero() {
        when(followRepository.findAllFollowingIds(42L)).thenReturn(Set.of());

        DiscussionCounts counts = service.getCountsForSymbol("ETH", 42L);

        assertThat(counts.yourNetwork()).isZero();
        verify(postRepository, org.mockito.Mockito.never()).countByCashtagAndAuthors(anyString(), any());
    }

    @Test
    @DisplayName("authed viewer with follows → by-authors count called with the same follow set")
    void getCountsForSymbol_authViewerWithFollows_callsRepoWithFollowedList() {
        Set<Long> follows = Set.of(11L, 12L, 13L);
        when(followRepository.findAllFollowingIds(42L)).thenReturn(follows);
        when(postRepository.countByCashtagAndAuthors(anyString(), eq(follows))).thenReturn(5L);

        DiscussionCounts counts = service.getCountsForSymbol("ETH", 42L);

        assertThat(counts.yourNetwork()).isEqualTo(5L);
        verify(postRepository).countByCashtagAndAuthors(eq("%$eth%"), eq(follows));
    }

    @Test
    @DisplayName("symbol is trimmed/uppercased; cashtag pattern is lowercased once for SQL LIKE")
    void getCountsForSymbol_normalizesSymbolAndPattern() {
        service.getCountsForSymbol("  EtH  ", null);

        // platform uses the pattern; comments/mentions use the uppercased symbol.
        verify(postRepository).countByCashtagGlobal(eq("%$eth%"));
        verify(commentRepository).countActiveByAssetSymbol(eq("ETH"));
        verify(commentRepository).countMentionsForSymbol(eq("ETH"));
    }

    @Test
    @DisplayName("response always carries all five fields; news is null in v1")
    void getCountsForSymbol_returnsAllFiveFields() {
        DiscussionCounts counts = service.getCountsForSymbol("ETH", null);

        // All five accessor methods present and non-throwing.
        assertThat(counts.yourNetwork()).isNotNull();
        assertThat(counts.platform()).isNotNull();
        assertThat(counts.comments()).isNotNull();
        assertThat(counts.mentions()).isNotNull();
        // News is the v1 null sentinel — FE branches on this.
        assertThat(counts.news()).isNull();
    }

    @Test
    @DisplayName("null symbol is treated as empty (defensive — controller shouldn't send this, but service is robust)")
    void getCountsForSymbol_nullSymbol_returnsAllZeroLikeCounts() {
        // Pattern becomes "%$%" — won't match anything legitimate, but more
        // importantly: the service must not NPE. Repos stubbed to default
        // long 0 by Mockito.
        DiscussionCounts counts = service.getCountsForSymbol(null, null);

        assertThat(counts).isNotNull();
        assertThat(counts.yourNetwork()).isZero();
        assertThat(counts.platform()).isZero();
        assertThat(counts.comments()).isZero();
        assertThat(counts.mentions()).isZero();
        assertThat(counts.news()).isNull();
    }
}
