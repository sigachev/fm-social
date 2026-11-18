package com.finmates.social.follow;

import com.finmates.social.block.BlockRepository;
import com.finmates.social.client.UserLookupCache;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * For fm-messaging (which knows people by username): ids, whether they're mates (mutual active
 * follows) and whether either blocked the other. Guarded by X-Internal-Secret like all /api/internal/**.
 */
@RestController
@RequestMapping("/api/internal/relationship")
@Tag(name = "Relationship (Internal)")
public class RelationshipInternalController {

    /** bAllowMessages: who b accepts messages from (EVERYONE, MATES, NOONE). */
    public record Relationship(Long aId, Long bId, boolean mates, boolean blocked, boolean aFollowsB, boolean bFollowsA,
                               String bAllowMessages) {}

    private final UserLookupCache users;
    private final FollowRepository follows;
    private final BlockRepository blocks;
    private final com.finmates.social.profile.ProfileRepository profiles;

    public RelationshipInternalController(UserLookupCache users, FollowRepository follows, BlockRepository blocks,
                                          com.finmates.social.profile.ProfileRepository profiles) {
        this.users = users;
        this.follows = follows;
        this.blocks = blocks;
        this.profiles = profiles;
    }

    /** Usernames (of those given) who turned off "Show when I'm online". */
    @GetMapping("/online-hidden")
    @Operation(summary = "Which of these usernames hide their online status")
    public java.util.List<String> onlineHidden(@RequestParam java.util.List<String> usernames) {
        return usernames.stream().limit(200).filter(u -> users.getByUsername(u)
                .map(UserLookupCache.UserSummary::userId)
                .flatMap(profiles::findById)
                .map(p -> !p.isShowOnlineStatus())
                .orElse(false)).toList();
    }

    @GetMapping
    @Operation(summary = "Relationship between two usernames")
    public Relationship get(@RequestParam String a, @RequestParam String b) {
        Long aId = users.getByUsername(a).map(UserLookupCache.UserSummary::userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown user " + a));
        Long bId = users.getByUsername(b).map(UserLookupCache.UserSummary::userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown user " + b));
        boolean ab = follows.existsByFollowerIdAndFollowedIdAndStatus(aId, bId, FollowStatus.ACTIVE);
        boolean ba = follows.existsByFollowerIdAndFollowedIdAndStatus(bId, aId, FollowStatus.ACTIVE);
        String allow = profiles.findById(bId).map(p -> p.getAllowMessages() == null ? "EVERYONE" : p.getAllowMessages().name()).orElse("EVERYONE");
        return new Relationship(aId, bId, ab && ba, blocks.existsBlockInEitherDirection(aId, bId), ab, ba, allow);
    }
}
