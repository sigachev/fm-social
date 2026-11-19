package com.finmates.social.notification;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Other services post notifications here. Secured by {@code X-Internal-Secret}
 * ({@code InternalSecretFilter} guards every {@code /api/internal/**} path).
 */
@RestController
@RequestMapping("/api/internal/notifications")
@Tag(name = "Notifications (Internal)")
public class NotificationInternalController {

    /** audience FOLLOWERS: every active follower of actorId; USER: just userId. */
    public record Request(String audience, Long userId, Long actorId, String type, String title, String body, String link) {}

    private final NotificationService service;

    public NotificationInternalController(NotificationService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Create a notification for one user or fan out to an actor's followers")
    public Map<String, Integer> create(@RequestBody Request r) {
        if (r == null || r.type() == null || r.title() == null || r.title().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type and title are required");
        }
        if (r.link() != null && !r.link().startsWith("/")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "link must be an app path");
        }
        // "{actor}" in a title or body becomes the actor's display name (callers don't know names).
        if (r.actorId() != null && (r.title().contains("{actor}") || (r.body() != null && r.body().contains("{actor}")))) {
            String name = service.nameOf(r.actorId());
            r = new Request(r.audience(), r.userId(), r.actorId(), r.type(), r.title().replace("{actor}", name),
                    r.body() == null ? null : r.body().replace("{actor}", name), r.link());
        }
        // "{actorUsername}" in a link becomes the actor's username (e.g. /u/{actorUsername}).
        if (r.actorId() != null && r.link() != null && r.link().contains("{actorUsername}")) {
            String u = service.usernameOf(r.actorId());
            r = new Request(r.audience(), r.userId(), r.actorId(), r.type(), r.title(), r.body(),
                    u == null ? null : r.link().replace("{actorUsername}", u));
        }
        if ("FOLLOWERS".equalsIgnoreCase(r.audience())) {
            if (r.actorId() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "actorId is required");
            return Map.of("recipients", service.notifyFollowers(r.actorId(), r.type(), r.title(), r.body(), r.link()));
        }
        if (r.userId() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required");
        service.notify(r.userId(), r.type(), r.actorId(), r.title(), r.body(), r.link());
        return Map.of("recipients", 1);
    }
}
