package com.finmates.social.follow;

import com.finmates.social.block.BlockRepository;
import com.finmates.social.common.PageResponse;
import com.finmates.social.common.exception.ForbiddenActionException;
import com.finmates.social.follow.dto.FollowResponse;
import com.finmates.social.follow.dto.RelationshipResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class FollowService {

    private final FollowRepository followRepository;
    private final BlockRepository blockRepository;

    public FollowService(FollowRepository followRepository, BlockRepository blockRepository) {
        this.followRepository = followRepository;
        this.blockRepository = blockRepository;
    }

    @Transactional
    public FollowResponse follow(Long followerId, Long followedId) {
        if (followerId.equals(followedId)) {
            throw new ForbiddenActionException("Cannot follow yourself");
        }
        if (blockRepository.existsBlockInEitherDirection(followerId, followedId)) {
            throw new ForbiddenActionException("Follow not allowed — a block exists between these users");
        }
        // DataIntegrityViolationException from UNIQUE constraint is handled by GlobalExceptionHandler → 409
        Follow follow = new Follow();
        follow.setFollowerId(followerId);
        follow.setFollowedId(followedId);
        return toResponse(followRepository.save(follow));
    }

    @Transactional
    public void unfollow(Long followerId, Long followedId) {
        followRepository.deleteByFollowerIdAndFollowedId(followerId, followedId);
    }

    @Transactional(readOnly = true)
    public PageResponse<FollowResponse> getFollowing(Long userId, Pageable pageable) {
        return PageResponse.from(followRepository.findByFollowerId(userId, pageable).map(this::toResponse));
    }

    @Transactional(readOnly = true)
    public PageResponse<FollowResponse> getFollowers(Long userId, Pageable pageable) {
        return PageResponse.from(followRepository.findByFollowedId(userId, pageable).map(this::toResponse));
    }

    @Transactional(readOnly = true)
    public RelationshipResponse getRelationship(Long viewerId, Long targetUserId) {
        boolean isFollowing = followRepository.existsByFollowerIdAndFollowedId(viewerId, targetUserId);
        boolean isFollowedBy = followRepository.existsByFollowerIdAndFollowedId(targetUserId, viewerId);
        return new RelationshipResponse(isFollowing, isFollowedBy);
    }

    public FollowResponse toResponse(Follow follow) {
        return new FollowResponse(
                follow.getId(),
                follow.getFollowerId(),
                follow.getFollowedId(),
                follow.getCreatedAt()
        );
    }
}
