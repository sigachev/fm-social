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

    public ReactionService(ReactionRepository reactionRepository,
                           PostRepository postRepository,
                           CommentRepository commentRepository) {
        this.reactionRepository = reactionRepository;
        this.postRepository = postRepository;
        this.commentRepository = commentRepository;
    }

    @Transactional
    public ReactionToggleResponse toggleReaction(Long userId, ReactionCreateRequest req) {
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
    public ReactionAggregateResponse getAggregates(ReactionTargetType targetType, Long targetId) {
        List<Reaction> reactions = reactionRepository.findByTargetTypeAndTargetId(targetType, targetId);

        Map<ReactionType, Long> counts = new EnumMap<>(ReactionType.class);
        for (ReactionType type : ReactionType.values()) {
            counts.put(type, 0L);
        }
        for (Reaction r : reactions) {
            counts.merge(r.getReactionType(), 1L, Long::sum);
        }

        long total = reactions.size();
        return new ReactionAggregateResponse(
                counts.get(ReactionType.BULLISH),
                counts.get(ReactionType.BEARISH),
                counts.get(ReactionType.FIRE),
                counts.get(ReactionType.DIAMOND_HANDS),
                counts.get(ReactionType.REKT),
                total
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void adjustReactionCount(ReactionTargetType targetType, Long targetId, int delta) {
        switch (targetType) {
            case POST -> postRepository.adjustReactionCount(targetId, delta);
            case COMMENT -> commentRepository.adjustReactionCount(targetId, delta);
        }
    }
}
