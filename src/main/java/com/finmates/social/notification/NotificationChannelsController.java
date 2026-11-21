package com.finmates.social.notification;

import com.finmates.social.common.security.AuthenticatedUser;
import com.finmates.social.notification.push.PushService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/** How notifications reach the signed-in user: per category bell / push / email, quiet hours, digest, browsers. */
@RestController
@Tag(name = "Notifications")
@PreAuthorize("isAuthenticated()")
public class NotificationChannelsController {

    /**
     * @param types        each category's actions
     * @param typeSettings the per-action choices the user made (an action without one follows its category)
     */
    public record ChannelsView(Map<String, NotificationDelivery.Channels> categories, NotificationDelivery.Prefs prefs,
                               boolean pushAvailable, String pushPublicKey, int pushDevices,
                               Map<String, java.util.List<String>> types, Map<String, Map<String, Boolean>> typeSettings) {}

    public record ChannelsUpdate(Map<String, Map<String, Boolean>> categories, NotificationDelivery.Prefs prefs) {}

    public record SubscriptionRequest(String endpoint, Map<String, String> keys, String userAgent) {}

    private final NotificationDelivery delivery;
    private final PushService push;
    private final AuthenticatedUser me;

    public NotificationChannelsController(NotificationDelivery delivery, PushService push, AuthenticatedUser me) {
        this.delivery = delivery;
        this.push = push;
        this.me = me;
    }

    @GetMapping("/api/notifications/channels")
    @Operation(summary = "Per-category channels, quiet hours, digest and push availability")
    public ChannelsView channels() {
        long u = me.currentUserId();
        Map<String, java.util.List<String>> types = new java.util.LinkedHashMap<>();
        for (String c : NotificationDelivery.ORDER) {
            types.put(c, NotificationService.CATEGORIES.getOrDefault(c, java.util.Set.of()).stream().sorted().toList());
        }
        return new ChannelsView(delivery.channels(u), delivery.prefs(u), push.configured(), push.publicKey(), push.devices(u),
                types, delivery.typeOverrides(u));
    }

    @PutMapping("/api/notifications/channels")
    @Operation(summary = "Change some channels and/or the prefs (send only what changes; prefs are sent whole)")
    public ChannelsView update(@RequestBody ChannelsUpdate req) {
        long u = me.currentUserId();
        try {
            if (req.categories() != null) delivery.updateChannels(u, req.categories());
            if (req.prefs() != null) delivery.updatePrefs(u, req.prefs());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return channels();
    }

    @PostMapping("/api/push/subscriptions")
    @Operation(summary = "Register this browser for push notifications")
    public ResponseEntity<Map<String, Object>> subscribe(@RequestBody SubscriptionRequest req) {
        if (!push.configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Browser notifications aren't set up yet.");
        try {
            push.subscribe(me.currentUserId(), req.endpoint(), req.keys() == null ? null : req.keys().get("p256dh"),
                    req.keys() == null ? null : req.keys().get("auth"), req.userAgent());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("devices", push.devices(me.currentUserId())));
    }

    @DeleteMapping("/api/push/subscriptions")
    @Operation(summary = "Stop push notifications for this browser")
    public Map<String, Object> unsubscribe(@RequestParam String endpoint) {
        push.unsubscribe(me.currentUserId(), endpoint);
        return Map.of("devices", push.devices(me.currentUserId()));
    }

    @PostMapping("/api/push/test")
    @Operation(summary = "Send a test push to all of your browsers")
    public Map<String, Object> test() {
        int n = push.send(me.currentUserId(), "Browser notifications are on",
                "This is how price alerts and other news will reach you, even with Finmates closed.", "/settings/notifications", "TEST", false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("delivered", n);
        return out;
    }
}
