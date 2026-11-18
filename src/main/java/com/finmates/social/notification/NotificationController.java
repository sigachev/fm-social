package com.finmates.social.notification;

import com.finmates.social.common.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** The signed-in user's notifications (header bell). */
@RestController
@RequestMapping("/api/notifications")
@Tag(name = "Notifications")
@PreAuthorize("isAuthenticated()")
public class NotificationController {

    private final NotificationService service;
    private final AuthenticatedUser me;

    public NotificationController(NotificationService service, AuthenticatedUser me) {
        this.service = service;
        this.me = me;
    }

    @GetMapping
    @Operation(summary = "Newest first; pass before=<id> for older ones")
    public List<NotificationService.Item> list(@RequestParam(defaultValue = "20") int limit,
                                               @RequestParam(required = false) Long before) {
        return service.list(me.currentUserId(), limit, before);
    }

    @GetMapping("/unread-count")
    public Map<String, Integer> unread() {
        return Map.of("count", service.unreadCount(me.currentUserId()));
    }

    @PostMapping("/read-all")
    public Map<String, Integer> readAll() {
        return Map.of("updated", service.markAllRead(me.currentUserId()));
    }

    @GetMapping("/settings")
    @Operation(summary = "Which notification categories are on: FOLLOWS, PEOPLES_SIGNALS, MY_SIGNALS, MESSAGES")
    public Map<String, Boolean> settings() {
        return service.settings(me.currentUserId());
    }

    @PutMapping("/settings")
    @Operation(summary = "Turn notification categories on or off (only the ones sent change)")
    public Map<String, Boolean> updateSettings(@RequestBody Map<String, Boolean> changes) {
        return service.updateSettings(me.currentUserId(), changes == null ? Map.of() : changes);
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> read(@PathVariable Long id) {
        service.markRead(me.currentUserId(), id);
        return ResponseEntity.noContent().build();
    }
}
