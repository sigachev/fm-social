package com.finmates.social.comment;

import com.finmates.social.comment.dto.CommentCreateRequest;
import com.finmates.social.comment.dto.CommentResponse;
import com.finmates.social.comment.dto.CommentUpdateRequest;
import com.finmates.social.edit.CommentEditRepository;
import com.finmates.social.post.PostRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito unit tests for {@link CommentService} — no Spring context.
 *
 * <p>Scope is limited to the cashtag-extraction wiring introduced in Phase 1.
 * Other branches of the service (edit-window enforcement, ownership checks,
 * admin moderation, soft-delete) are not exercised here; they predate this
 * change and didn't ship with a Mockito test suite. Repository slice tests
 * for the new GIN-indexed column are deferred to a separate ticket that
 * stands up a Testcontainers PG (see {@code fm-social/CLAUDE.md} follow-ups).</p>
 *
 * <p>Each test uses {@link ArgumentCaptor} to inspect the {@link Comment}
 * passed to {@code commentRepository.save(...)} — that's the contract the
 * extractor wiring has to satisfy.</p>
 */
@ExtendWith(MockitoExtension.class)
class CommentServiceTest {

    @Mock CommentRepository commentRepository;
    @Mock CommentEditRepository commentEditRepository;
    @Mock PostRepository postRepository;

    @InjectMocks CommentService service;

    @BeforeEach
    void setUp() {
        // editWindowMinutes is normally bound by @Value; tests use a wide
        // window so updateComment never trips the EditWindowExpiredException.
        ReflectionTestUtils.setField(service, "editWindowMinutes", 60_000);
        // commentRepository.save returns its argument — service code reads the
        // saved Comment back to build the response DTO.
        when(commentRepository.save(any(Comment.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // ── createComment ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("createComment populates extractedCashtags from the request content")
    void createComment_populatesExtractedCashtags() {
        CommentCreateRequest req = assetCreateRequest("buying $ETH", null);

        service.createComment(7L, "alice", req);

        Comment saved = captureSaved();
        assertThat(saved.getExtractedCashtags()).containsExactly("ETH");
    }

    @Test
    @DisplayName("createComment with content that has no cashtags writes an empty list, not null")
    void createComment_noCashtags_populatesEmptyList() {
        CommentCreateRequest req = assetCreateRequest("no tags here", null);

        service.createComment(7L, "alice", req);

        Comment saved = captureSaved();
        assertThat(saved.getExtractedCashtags()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("createComment on a reply (parent_id set) extracts cashtags too")
    void createComment_replyAlsoExtracts() {
        CommentCreateRequest req = assetCreateRequest("$BTC reply", 99L);

        service.createComment(7L, "alice", req);

        Comment saved = captureSaved();
        assertThat(saved.getParentId()).isEqualTo(99L);
        assertThat(saved.getExtractedCashtags()).containsExactly("BTC");
    }

    @Test
    @DisplayName("createComment deduplicates and sorts cashtags coming out of the extractor")
    void createComment_dedupesAndSorts() {
        CommentCreateRequest req = assetCreateRequest("$eth $BTC $ETH $sol", null);

        service.createComment(7L, "alice", req);

        Comment saved = captureSaved();
        assertThat(saved.getExtractedCashtags()).containsExactly("BTC", "ETH", "SOL");
    }

    // ── updateComment ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("updateComment replaces the cashtag list — old entries dropped, not merged")
    void updateComment_replacesCashtags() {
        Comment existing = existingAssetComment(11L, "previously $ETH and $BTC", List.of("BTC", "ETH"));
        when(commentRepository.findById(11L)).thenReturn(Optional.of(existing));

        CommentUpdateRequest req = updateRequest("only $SOL now");

        CommentResponse result = service.updateComment(11L, existing.getAuthorId(), req);

        Comment saved = captureSaved();
        assertThat(saved.getExtractedCashtags())
                .as("replacement semantics: stale BTC/ETH must be gone")
                .containsExactly("SOL");
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("updateComment that removes every cashtag yields an empty list")
    void updateComment_removeAllCashtags() {
        Comment existing = existingAssetComment(12L, "had $ETH", List.of("ETH"));
        when(commentRepository.findById(12L)).thenReturn(Optional.of(existing));

        CommentUpdateRequest req = updateRequest("no tags anymore");

        service.updateComment(12L, existing.getAuthorId(), req);

        Comment saved = captureSaved();
        assertThat(saved.getExtractedCashtags()).isEmpty();
    }

    @Test
    @DisplayName("updateComment populates cashtags when prior version had none")
    void updateComment_addCashtagsFromBlankState() {
        Comment existing = existingAssetComment(13L, "no tags before", List.of());
        when(commentRepository.findById(13L)).thenReturn(Optional.of(existing));

        CommentUpdateRequest req = updateRequest("now mentioning $ARB");

        service.updateComment(13L, existing.getAuthorId(), req);

        Comment saved = captureSaved();
        assertThat(saved.getExtractedCashtags()).containsExactly("ARB");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Comment captureSaved() {
        ArgumentCaptor<Comment> cap = ArgumentCaptor.forClass(Comment.class);
        // updateComment calls save() once; createComment also once. Tests
        // here only ever exercise one path, so capturing all invocations and
        // taking the last is the simplest cross-cutting assertion.
        verify(commentRepository, times(1)).save(cap.capture());
        return cap.getValue();
    }

    private CommentCreateRequest assetCreateRequest(String content, Long parentId) {
        CommentCreateRequest r = new CommentCreateRequest();
        r.setTargetType(CommentTargetType.ASSET);
        r.setTargetSymbol("BTC");
        r.setParentId(parentId);
        r.setContent(content);
        return r;
    }

    private CommentUpdateRequest updateRequest(String content) {
        CommentUpdateRequest r = new CommentUpdateRequest();
        r.setContent(content);
        return r;
    }

    private Comment existingAssetComment(Long id, String content, List<String> cashtags) {
        Comment c = new Comment();
        c.setId(id);
        c.setAuthorId(7L);
        c.setAuthorUsername("alice");
        c.setTargetType(CommentTargetType.ASSET);
        c.setTargetSymbol("BTC");
        c.setContent(content);
        c.setStatus(CommentStatus.ACTIVE);
        c.setExtractedCashtags(new java.util.ArrayList<>(cashtags));
        // createdAt drives the edit-window check; setUp's wide window means
        // any non-null value is fine.
        c.setCreatedAt(OffsetDateTime.now().minusMinutes(1));
        c.setUpdatedAt(c.getCreatedAt());
        return c;
    }
}
