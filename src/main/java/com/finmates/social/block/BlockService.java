package com.finmates.social.block;

import com.finmates.social.block.dto.BlockResponse;
import com.finmates.social.common.PageResponse;
import com.finmates.social.common.exception.ForbiddenActionException;
import com.finmates.social.follow.FollowRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class BlockService {

    private final BlockRepository blockRepository;
    private final FollowRepository followRepository;

    public BlockService(BlockRepository blockRepository, FollowRepository followRepository) {
        this.blockRepository = blockRepository;
        this.followRepository = followRepository;
    }

    @Transactional
    public BlockResponse block(Long blockerId, Long blockedId) {
        if (blockerId.equals(blockedId)) {
            throw new ForbiddenActionException("Cannot block yourself");
        }
        // DataIntegrityViolationException from UNIQUE constraint → GlobalExceptionHandler → 409
        Block block = new Block();
        block.setBlockerId(blockerId);
        block.setBlockedId(blockedId);
        Block saved = blockRepository.save(block);

        // Remove follow relationship in both directions
        followRepository.deleteMutualFollows(blockerId, blockedId);

        return toResponse(saved);
    }

    @Transactional
    public void unblock(Long blockerId, Long blockedId) {
        blockRepository.findByBlockerIdAndBlockedId(blockerId, blockedId)
                .ifPresent(blockRepository::delete);
    }

    @Transactional(readOnly = true)
    public PageResponse<BlockResponse> getBlocks(Long blockerId, Pageable pageable) {
        return PageResponse.from(blockRepository.findByBlockerId(blockerId, pageable).map(this::toResponse));
    }

    public BlockResponse toResponse(Block block) {
        return new BlockResponse(
                block.getId(),
                block.getBlockerId(),
                block.getBlockedId(),
                block.getCreatedAt()
        );
    }
}
