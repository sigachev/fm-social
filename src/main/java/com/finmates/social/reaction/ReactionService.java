package com.finmates.social.reaction;

import com.finmates.social.comment.CommentRepository;
import com.finmates.social.common.exception.ResourceNotFoundException;
import com.finmates.social.post.PostRepository;
import com.finmates.social.reaction.dto.ReactionAggregateResponse;
import com.finmates.social.reaction.dto.ReactionCreateRequest;
import com.finmates.social.reaction.dto.ReactionToggleResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class ReactionService {

    private final ReactionRepository reactionRepository;
    private final PostRepository postRepository;
    private final CommentRepository commentRepository;

    private final com.finmates.social.comment.CommentService comments;

    public ReactionService(ReactionRepository reactionRepository,
                           PostRepository postRepository,
                           CommentRepository commentRepository,
                           com.finmates.social.comment.CommentService comments) {
        this.comments = comments;
        this.reactionRepository = reactionRepository;
        this.postRepository = postRepository;
        this.commentRepository = commentRepository;
    }

    @Transactional
    public ReactionToggleResponse toggleReaction(Long userId, ReactionCreateRequest req) {
        requireCanSee(userId, req.getTargetType(), req.getTargetId());
        Optional<Reaction> existing = reactionRepository.findByUserIdAndTargetTypeAndTargetIdAndReactionType(
                userId, req.getTargetType(), req.getTargetId(), req.getReactionType());

        if (existing.isPresent()) {
            reactionRepository.delete(existing.get());
            adjustReactionCount(req.getTargetType(), req.getTargetId(), -1);
            return new ReactionToggleResponse(false);
        } else {
            Reaction reaction = new Reaction();
            reaction.setUserId(userId);
            reaction.setTargetType(req.getTargetType());
            reaction.setTargetId(req.getTargetId());
            reaction.setReactionType(req.getReactionType());
            reactionRepository.save(reaction);
            adjustReactionCount(req.getTargetType(), req.getTargetId(), 1);
            return new ReactionToggleResponse(true);
        }
    }

    @Transactional(readOnly = true)
    public ReactionAggregateResponse getAggregates(ReactionTargetType targetType, Long targetId, Long viewerId) {
        requireCanSee(viewerId, targetType, targetId);
        List<Reaction> reactions = reactionRepository.findByTargetTypeAndTargetId(targetType, targetId);

        Map<ReactionType, Long> counts = new EnumMap<>(ReactionType.class);
        for (ReactionType type : ReactionType.values()) {
            counts.put(type, 0L);
        }
        String myReactionType = null;
        for (Reaction r : reactions) {
            counts.merge(r.getReactionType(), 1L, Long::sum);
            if (viewerId != null && viewerId.equals(r.getUserId())) {
                myReactionType = r.getReactionType().name();
            }
        }

        long total = reactions.size();
        return new ReactionAggregateResponse(
                counts.get(ReactionType.BULLISH),
                counts.get(ReactionType.BEARISH),
                counts.get(ReactionType.FIRE),
                counts.get(ReactionType.DIAMOND_HANDS),
                counts.get(ReactionType.REKT),
                total,
                myReactionType
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Reactions follow what they are on: a post's audience, or the post or portfolio a comment is on. */
    private void requireCanSee(Long viewerId, ReactionTargetType type, Long targetId) {
        if (type == ReactionTargetType.POST) {
            comments.requireCanSee(viewerId, com.finmates.social.comment.CommentTargetType.POST, targetId);
        } else if (type == ReactionTargetType.COMMENT) {
            var c = targetId == null ? null : commentRepository.findById(targetId).orElse(null);
            if (c == null) throw new com.finmates.social.common.exception.ResourceNotFoundException("Not found");
            comments.requireCanSee(viewerId, c.getTargetType(), c.getTargetId());
        }
    }

    private void adjustReactionCount(ReactionTargetType targetType, Long targetId, int delta) {
        switch (targetType) {
            case POST -> postRepository.adjustReactionCount(targetId, delta);
            case COMMENT -> commentRepository.adjustReactionCount(targetId, delta);
        }
    }
}
