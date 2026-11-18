package com.finmates.social.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.finmates.social.block.BlockRepository;
import com.finmates.social.common.security.AuthenticatedUser;
import com.finmates.social.follow.FollowRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.*;

/**
 * Community → Signals. Signals come from finmates-crypto (public reads); this adds who posted
 * them (name, username, mate or not) and picks whose signals to show:
 * following = people you follow, mates = mutual follows, everyone = all users minus anyone
 * blocked either way.
 */
@Slf4j
@RestController
@RequestMapping("/api/signals-feed")
@Tag(name = "Signals feed")
@PreAuthorize("isAuthenticated()")
public class SignalsFeedController {

    private final FollowRepository follows;
    private final BlockRepository blocks;
    private final AuthenticatedUser me;
    private final NotificationService names;
    private final WebClient crypto;

    public SignalsFeedController(FollowRepository follows, BlockRepository blocks, AuthenticatedUser me,
                                 NotificationService names, @Qualifier("cryptoServiceWebClient") WebClient crypto) {
        this.follows = follows;
        this.blocks = blocks;
        this.me = me;
        this.names = names;
        this.crypto = crypto;
    }

    @GetMapping
    @Operation(summary = "Signals from people you follow, your mates or everyone; open, finished or all")
    public JsonNode feed(@RequestParam(defaultValue = "following") String scope,
                         @RequestParam(defaultValue = "all") String status,
                         @RequestParam(required = false) Long before,
                         @RequestParam(defaultValue = "20") int limit) {
        if (!status.matches("open|finished|all")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be open, finished or all");
        Long viewer = me.currentUserId();
        Set<Long> following = follows.findAllFollowingIds(viewer);
        Set<Long> mates = following.isEmpty() ? Set.of() : new HashSet<>(follows.findMateIdsAmong(viewer, following));

        List<Long> ids = switch (scope) {
            case "everyone" -> null;
            case "mates" -> new ArrayList<>(mates);
            case "following" -> new ArrayList<>(following);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scope must be following, mates or everyone");
        };
        if (ids != null && ids.isEmpty()) return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        if (ids != null && ids.size() > 500) ids = ids.subList(0, 500);

        int max = Math.max(1, Math.min(limit, 50));
        // For everyone, over-fetch a little so dropping blocked authors doesn't leave a short page.
        int fetch = ids == null ? Math.min(50, max + 10) : max;
        List<Long> finalIds = ids;
        JsonNode rows = crypto.get().uri(b -> {
                    b.path("/api/signals/feed").queryParam("status", status).queryParam("limit", fetch);
                    if (before != null) b.queryParam("before", before);
                    if (finalIds != null) b.queryParam("userIds", String.join(",", finalIds.stream().map(String::valueOf).toList()));
                    return b.build();
                })
                .retrieve().bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(10))
                .onErrorResume(e -> {
                    log.warn("event=signals_feed_failed scope={} error={}", scope, e.toString());
                    return reactor.core.publisher.Mono.error(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Signals are unavailable right now"));
                })
                .block();

        ArrayNode out = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        if (rows == null || !rows.isArray()) return out;
        Map<Long, Boolean> blocked = new HashMap<>();
        for (JsonNode r : rows) {
            if (out.size() >= max) break;
            long author = r.path("userId").asLong();
            if (author != viewer && blocked.computeIfAbsent(author, a -> blocks.existsBlockInEitherDirection(viewer, a))) continue;
            ObjectNode o = ((ObjectNode) r).deepCopy();
            o.put("authorName", author == viewer ? "You" : names.nameOf(author));
            String username = names.usernameOf(author);
            if (username != null) o.put("authorUsername", username);
            o.put("authorIsMate", mates.contains(author));
            o.put("authorIsFollowed", following.contains(author));
            o.put("authorIsMe", author == viewer);
            out.add(o);
        }
        return out;
    }
}
