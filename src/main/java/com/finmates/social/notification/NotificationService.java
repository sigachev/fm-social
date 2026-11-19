package com.finmates.social.notification;

import com.finmates.social.client.UserLookupCache;
import com.finmates.social.follow.FollowRepository;
import com.finmates.social.profile.ProfileRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * In-app notifications. Plain JDBC on the {@code notifications} table (V18): writes are
 * fire-and-forget from the caller's point of view, so a failure is logged and never breaks
 * the action that caused it (a follow, a signal post).
 */
@Slf4j
@Service
public class NotificationService {

    /** Wire shape for the bell. {@code actor*} fields are null for system notifications. */
    public record Item(Long id, String type, Long actorId, String actorUsername, String actorName,
                       String title, String body, String link, Instant createdAt, boolean read) {}

    private static final int MAX_FANOUT = 5_000;

    /** Settings categories, each covering some notification types. */
    public static final java.util.Map<String, java.util.Set<String>> CATEGORIES = java.util.Map.of(
            "FOLLOWS", java.util.Set.of("NEW_FOLLOWER", "NEW_MATE", "FOLLOW_REQUEST", "FOLLOW_ACCEPTED"),
            "PEOPLES_SIGNALS", java.util.Set.of("SIGNAL_POSTED", "SIGNAL_HIT", "SIGNAL_STOPPED", "SIGNAL_BACKED"),
            "MY_SIGNALS", java.util.Set.of("MY_SIGNAL_HIT", "MY_SIGNAL_STOPPED", "MY_SIGNAL_EXPIRED"),
            "MESSAGES", java.util.Set.of("MESSAGE", "MESSAGE_REQUEST"),
            "PRICE_ALERTS", java.util.Set.of("PRICE_ALERT"),
            "LIQUIDITY", java.util.Set.of("LP_NEAR_EDGE", "LP_OUT_OF_RANGE", "LP_BACK_IN_RANGE"),
            "DIGEST", java.util.Set.of("DIGEST"));

    /** Which category a notification belongs to; the author's own signal results are MY_SIGNALS. */
    static String categoryOf(String type, boolean aboutSelf) {
        if (type == null) return null;
        if (aboutSelf && type.startsWith("SIGNAL_")) return "MY_SIGNALS";
        for (var e : CATEGORIES.entrySet()) if (e.getValue().contains(type)) return e.getKey();
        return null;
    }

    private final JdbcTemplate jdbc;
    private final FollowRepository follows;
    private final UserLookupCache users;
    private final ProfileRepository profiles;
    private final NotificationStream stream;
    private final NotificationDelivery delivery;

    public NotificationService(JdbcTemplate jdbc, FollowRepository follows, UserLookupCache users, ProfileRepository profiles,
                               NotificationStream stream, NotificationDelivery delivery) {
        this.stream = stream;
        this.delivery = delivery;
        this.jdbc = jdbc;
        this.follows = follows;
        this.users = users;
        this.profiles = profiles;
    }

    public void notify(Long userId, String type, Long actorId, String title, String body, String link) {
        if (userId == null || Objects.equals(userId, actorId)) return;
        // A notification with no actor about a signal is the author's own result (MY_SIGNALS).
        if (!enabled(userId, categoryOf(type, actorId == null))) return;
        insert(List.of(userId), type, actorId, title, body, link);
    }

    // ── settings ──
    public java.util.Map<String, Boolean> settings(Long userId) {
        java.util.Map<String, Boolean> out = new java.util.LinkedHashMap<>();
        for (String c : List.of("FOLLOWS", "PEOPLES_SIGNALS", "MY_SIGNALS", "MESSAGES", "PRICE_ALERTS", "LIQUIDITY", "DIGEST")) out.put(c, true);
        jdbc.query("SELECT category, enabled FROM notification_settings WHERE user_id = ?",
                rs -> { if (out.containsKey(rs.getString(1))) out.put(rs.getString(1), rs.getBoolean(2)); }, userId);
        return out;
    }

    public java.util.Map<String, Boolean> updateSettings(Long userId, java.util.Map<String, Boolean> changes) {
        changes.forEach((c, on) -> {
            if (!CATEGORIES.containsKey(c) || on == null) return;
            jdbc.update("INSERT INTO notification_settings (user_id, category, enabled) VALUES (?, ?, ?) "
                    + "ON CONFLICT (user_id, category) DO UPDATE SET enabled = EXCLUDED.enabled", userId, c, on);
        });
        return settings(userId);
    }

    private boolean enabled(Long userId, String category) {
        if (category == null) return true;
        try {
            List<Boolean> r = jdbc.queryForList("SELECT enabled FROM notification_settings WHERE user_id = ? AND category = ?", Boolean.class, userId, category);
            return r.isEmpty() || Boolean.TRUE.equals(r.get(0));
        } catch (Exception e) {
            return true;
        }
    }

    /** Every ACTIVE follower of {@code actorId} (the actor themself excluded). Returns how many got it. */
    public int notifyFollowers(Long actorId, String type, String title, String body, String link) {
        List<Long> ids = follows.findAllFollowerIds(actorId).stream()
                .filter(id -> !Objects.equals(id, actorId)).distinct().limit(MAX_FANOUT).toList();
        String category = categoryOf(type, false);
        if (category != null && !ids.isEmpty()) {
            // Drop followers who switched this category off.
            String in = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
            List<Object> args = new ArrayList<>(ids);
            args.add(category);
            java.util.Set<Long> off = new java.util.HashSet<>(jdbc.queryForList(
                    "SELECT user_id FROM notification_settings WHERE user_id IN (" + in + ") AND category = ? AND enabled = FALSE",
                    Long.class, args.toArray()));
            if (!off.isEmpty()) ids = ids.stream().filter(id -> !off.contains(id)).toList();
        }
        insert(ids, type, actorId, title, body, link);
        return ids.size();
    }

    private void insert(List<Long> userIds, String type, Long actorId, String title, String body, String link) {
        if (userIds.isEmpty()) return;
        try {
            List<Object[]> rows = new ArrayList<>(userIds.size());
            for (Long u : userIds) rows.add(new Object[]{u, cut(type, 40), actorId, cut(title, 200), cut(body, 500), cut(link, 300)});
            jdbc.batchUpdate("INSERT INTO notifications (user_id, type, actor_id, title, body, link) VALUES (?, ?, ?, ?, ?, ?)", rows);
            stream.publish(userIds, new NotificationStream.Event(cut(type, 40), actorId, cut(title, 200), cut(body, 500), cut(link, 300), Instant.now()));
            // Browser push and email, by each person's choices (background).
            delivery.dispatch(userIds, type, categoryOf(type, actorId == null), cut(title, 200), cut(body, 500), cut(link, 300));
        } catch (Exception e) {
            log.warn("event=notification_insert_failed type={} actor={} recipients={} error={}", type, actorId, userIds.size(), e.toString());
        }
    }

    public List<Item> list(Long userId, int limit, Long beforeId) {
        int max = Math.max(1, Math.min(limit, 100));
        List<Item> raw = beforeId == null
                ? jdbc.query("SELECT * FROM notifications WHERE user_id = ? ORDER BY id DESC LIMIT ?", (rs, i) -> row(rs), userId, max)
                : jdbc.query("SELECT * FROM notifications WHERE user_id = ? AND id < ? ORDER BY id DESC LIMIT ?", (rs, i) -> row(rs), userId, beforeId, max);
        return raw.stream().map(this::withActor).toList();
    }

    public int unreadCount(Long userId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND read_at IS NULL", Integer.class, userId);
        return n == null ? 0 : n;
    }

    public int markAllRead(Long userId) {
        return jdbc.update("UPDATE notifications SET read_at = now() WHERE user_id = ? AND read_at IS NULL", userId);
    }

    public boolean markRead(Long userId, Long id) {
        return jdbc.update("UPDATE notifications SET read_at = now() WHERE id = ? AND user_id = ? AND read_at IS NULL", id, userId) > 0;
    }

    private Item row(java.sql.ResultSet rs) throws java.sql.SQLException {
        long actor = rs.getLong("actor_id");
        Timestamp at = rs.getTimestamp("created_at");
        return new Item(rs.getLong("id"), rs.getString("type"), rs.wasNull() ? null : actor, null, null,
                rs.getString("title"), rs.getString("body"), rs.getString("link"),
                at == null ? null : at.toInstant(), rs.getTimestamp("read_at") != null);
    }

    private Item withActor(Item n) {
        if (n.actorId() == null) return n;
        String username = users.getByUserId(n.actorId()).map(UserLookupCache.UserSummary::username).orElse(null);
        String name = profiles.findById(n.actorId())
                .map(p -> p.getDisplayName() != null && !p.getDisplayName().isBlank() ? p.getDisplayName().trim() : null)
                .orElse(null);
        return new Item(n.id(), n.type(), n.actorId(), username, name != null ? name : username,
                n.title(), n.body(), n.link(), n.createdAt(), n.read());
    }

    /** Display name for messages written by fm-social itself ("Kenji started following you"). */
    public String nameOf(Long userId) {
        String name = profiles.findById(userId)
                .map(p -> p.getDisplayName() != null && !p.getDisplayName().isBlank() ? p.getDisplayName().trim() : null)
                .orElse(null);
        if (name != null) return name;
        return users.getByUserId(userId).map(UserLookupCache.UserSummary::username).map(u -> "@" + u).orElse("Someone");
    }

    public String usernameOf(Long userId) {
        return users.getByUserId(userId).map(UserLookupCache.UserSummary::username).orElse(null);
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
