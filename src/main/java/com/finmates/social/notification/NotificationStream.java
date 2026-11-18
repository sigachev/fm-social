package com.finmates.social.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Live notifications: each open Finmates tab holds one server-sent-events connection
 * (GET /api/notifications/stream) and gets a "notification" event the moment one is stored for its
 * user. The bell still loads from the table; this only makes new ones show up at once.
 *
 * <p>In memory, per pod. With more than one fm-social replica a user connected to another pod sees
 * the notification on the next poll instead (the bell keeps polling as a fallback).
 */
@Slf4j
@Component
public class NotificationStream {

    /** What a tab receives; the stored row (with its id) comes from GET /api/notifications. */
    public record Event(String type, Long actorId, String title, String body, String link, Instant createdAt) {}

    static final long TIMEOUT_MS = 30 * 60_000L;
    private static final int MAX_PER_USER = 8;

    private final Map<Long, Set<SseEmitter>> byUser = new ConcurrentHashMap<>();

    public SseEmitter open(Long userId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        Set<SseEmitter> set = byUser.computeIfAbsent(userId, k -> new CopyOnWriteArraySet<>());
        if (set.size() >= MAX_PER_USER) {
            // Too many tabs: drop the oldest-looking one rather than grow without bound.
            set.stream().findFirst().ifPresent(old -> { set.remove(old); old.complete(); });
        }
        set.add(emitter);
        Runnable drop = () -> remove(userId, emitter);
        emitter.onCompletion(drop);
        emitter.onTimeout(drop);
        emitter.onError(e -> drop.run());
        try {
            emitter.send(SseEmitter.event().name("ready").data(Map.of("at", Instant.now().toString())));
        } catch (IOException e) {
            drop.run();
        }
        return emitter;
    }

    public void publish(Collection<Long> userIds, Event event) {
        for (Long id : userIds) {
            Set<SseEmitter> set = byUser.get(id);
            if (set == null || set.isEmpty()) continue;
            for (SseEmitter e : List.copyOf(set)) {
                try {
                    e.send(SseEmitter.event().name("notification").data(event));
                } catch (Exception ex) {
                    remove(id, e);
                }
            }
        }
    }

    /** A comment line every 25s keeps proxies from closing idle connections. */
    @Scheduled(fixedDelay = 25_000)
    public void heartbeat() {
        byUser.forEach((id, set) -> {
            for (SseEmitter e : List.copyOf(set)) {
                try {
                    e.send(SseEmitter.event().comment("hb"));
                } catch (Exception ex) {
                    remove(id, e);
                }
            }
        });
    }

    int connections() {
        return byUser.values().stream().mapToInt(Set::size).sum();
    }

    private void remove(Long userId, SseEmitter e) {
        Set<SseEmitter> set = byUser.get(userId);
        if (set == null) return;
        set.remove(e);
        if (set.isEmpty()) byUser.remove(userId, set);
    }
}
