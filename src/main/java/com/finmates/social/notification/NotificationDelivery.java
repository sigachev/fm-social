package com.finmates.social.notification;

import com.finmates.social.notification.push.PushService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Where a stored notification goes besides the bell: browser push and email, by category and by the
 * user's choices, and never as a push during their quiet hours. Email goes through finmates-main's
 * existing mail sender (POST /api/internal/email/notification) to the verified address only.
 *
 * <p>Defaults: push on for everything except "signals from people you follow"; email off.
 */
@Slf4j
@Service
public class NotificationDelivery {

    public static final List<String> ORDER = List.of("PRICE_ALERTS", "LIQUIDITY", "MESSAGES", "REPLIES", "FOLLOWS", "MY_SIGNALS", "PEOPLES_SIGNALS", "DIGEST");

    public record Channels(boolean inApp, boolean push, boolean email) {}

    public record Prefs(Integer quietStart, Integer quietEnd, String timeZone, boolean digest, int digestHour) {}

    static final Set<String> URGENT = Set.of("PRICE_ALERT", "LP_OUT_OF_RANGE", "MESSAGE", "MESSAGE_REQUEST");

    private final JdbcTemplate jdbc;
    private final PushService push;
    private final WebClient main;
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "notification-delivery");
        t.setDaemon(true);
        return t;
    });

    public NotificationDelivery(JdbcTemplate jdbc, PushService push, @Qualifier("mainServiceWebClient") WebClient main) {
        this.jdbc = jdbc;
        this.push = push;
        this.main = main;
    }

    static boolean pushDefault(String category) {
        return !"PEOPLES_SIGNALS".equals(category);
    }

    // ── choices ──────────────────────────────────────────────────────────────

    public Map<String, Channels> channels(long userId) {
        Map<String, Channels> out = new LinkedHashMap<>();
        for (String c : ORDER) out.put(c, new Channels(true, pushDefault(c), false));
        jdbc.query("SELECT category, enabled, push, email FROM notification_settings WHERE user_id = ?", rs -> {
            String c = rs.getString(1);
            if (!out.containsKey(c)) return;
            Boolean p = (Boolean) rs.getObject(3), e = (Boolean) rs.getObject(4);
            out.put(c, new Channels(rs.getBoolean(2), p == null ? pushDefault(c) : p, e != null && e));
        }, userId);
        return out;
    }

    public void updateChannels(long userId, Map<String, Map<String, Boolean>> changes) {
        changes.forEach((c, ch) -> {
            if (!ORDER.contains(c) || ch == null) return;
            Boolean inApp = ch.get("inApp"), p = ch.get("push"), e = ch.get("email");
            jdbc.update("""
                    INSERT INTO notification_settings (user_id, category, enabled, push, email) VALUES (?, ?, COALESCE(?, TRUE), ?, ?)
                    ON CONFLICT (user_id, category) DO UPDATE SET
                        enabled = COALESCE(?, notification_settings.enabled),
                        push = COALESCE(?, notification_settings.push),
                        email = COALESCE(?, notification_settings.email)""",
                    userId, c, inApp, p, e, inApp, p, e);
        });
    }

    public Prefs prefs(long userId) {
        List<Prefs> r = jdbc.query("SELECT quiet_start, quiet_end, time_zone, digest, digest_hour FROM notification_prefs WHERE user_id = ?",
                (rs, i) -> new Prefs((Integer) rs.getObject(1), (Integer) rs.getObject(2), rs.getString(3), rs.getBoolean(4), rs.getInt(5)), userId);
        return r.isEmpty() ? new Prefs(null, null, null, false, 8) : r.get(0);
    }

    public Prefs updatePrefs(long userId, Prefs p) {
        Integer qs = p.quietStart(), qe = p.quietEnd();
        if ((qs == null) != (qe == null)) throw new IllegalArgumentException("Set both ends of quiet hours, or neither.");
        if (qs != null && (qs < 0 || qs > 23 || qe < 0 || qe > 23 || qs.equals(qe))) throw new IllegalArgumentException("Quiet hours must be two different hours, 0 to 23.");
        String tz = p.timeZone();
        if (tz != null) {
            try { ZoneId.of(tz); } catch (Exception e) { throw new IllegalArgumentException("Unknown time zone."); }
        }
        int hour = Math.max(0, Math.min(23, p.digestHour()));
        jdbc.update("""
                INSERT INTO notification_prefs (user_id, quiet_start, quiet_end, time_zone, digest, digest_hour, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (user_id) DO UPDATE SET quiet_start = EXCLUDED.quiet_start, quiet_end = EXCLUDED.quiet_end,
                    time_zone = EXCLUDED.time_zone, digest = EXCLUDED.digest, digest_hour = EXCLUDED.digest_hour, updated_at = now()""",
                userId, qs, qe, tz, p.digest(), hour);
        return prefs(userId);
    }

    /** Quiet hours wrap midnight when start > end (23 → 7). */
    static boolean quiet(Prefs p, Instant now) {
        if (p == null || p.quietStart() == null || p.quietEnd() == null) return false;
        ZoneId z;
        try { z = p.timeZone() == null ? ZoneOffset.UTC : ZoneId.of(p.timeZone()); } catch (Exception e) { z = ZoneOffset.UTC; }
        int h = now.atZone(z).getHour();
        int s = p.quietStart(), e = p.quietEnd();
        return s < e ? (h >= s && h < e) : (h >= s || h < e);
    }

    // ── delivery ─────────────────────────────────────────────────────────────

    /** Called after notifications are stored for {@code userIds}. Runs in the background. */
    public void dispatch(List<Long> userIds, String type, String category, String title, String body, String link) {
        if (userIds.isEmpty()) return;
        String cat = category == null ? ("DIGEST".equals(type) ? "DIGEST" : null) : category;
        List<Long> ids = List.copyOf(userIds);
        pool.submit(() -> {
            try {
                deliver(ids, type, cat, title, body, link);
            } catch (Exception e) {
                log.warn("event=notification_delivery_failed type={} recipients={} error={}", type, ids.size(), e.toString());
            }
        });
    }

    private void deliver(List<Long> ids, String type, String cat, String title, String body, String link) {
        String in = String.join(",", Collections.nCopies(ids.size(), "?"));
        // Push: only people with a registered browser.
        Set<Long> withDevice = new HashSet<>();
        if (push.configured()) {
            withDevice.addAll(jdbc.queryForList("SELECT DISTINCT user_id FROM push_subscriptions WHERE user_id IN (" + in + ")", Long.class, ids.toArray()));
        }
        // Explicit per-category choices for these people.
        Map<Long, Boolean> pushSet = new HashMap<>(), emailSet = new HashMap<>();
        if (cat != null) {
            List<Object> args = new ArrayList<>(ids);
            args.add(cat);
            jdbc.query("SELECT user_id, push, email FROM notification_settings WHERE user_id IN (" + in + ") AND category = ?", rs -> {
                if (rs.getObject(2) != null) pushSet.put(rs.getLong(1), rs.getBoolean(2));
                if (rs.getObject(3) != null) emailSet.put(rs.getLong(1), rs.getBoolean(3));
            }, args.toArray());
        }
        boolean urgent = URGENT.contains(type);
        Instant now = Instant.now();
        for (Long u : withDevice) {
            boolean on = pushSet.getOrDefault(u, cat == null || pushDefault(cat));
            if (!on || quiet(prefs(u), now)) continue;
            push.send(u, title, body, link, type, urgent);
        }
        emailSet.forEach((u, on) -> {
            if (!on) return;
            try {
                main.post().uri("/api/internal/email/notification")
                        .bodyValue(Map.of("userId", u, "subject", title, "title", title, "body", body == null ? "" : body,
                                "link", link == null ? "/" : link, "category", cat))
                        .retrieve().toBodilessEntity().block(Duration.ofSeconds(15));
            } catch (Exception e) {
                log.warn("event=notification_email_failed user={} error={}", u, e.toString());
            }
        });
    }
}
