package com.finmates.social.notification;

import com.finmates.social.profile.ProfileMoneyController;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The daily digest, for people who turn it on: one notification at their chosen local hour
 * (default 8:00) with their 30-day result against their mates, alerts and range changes of the last
 * day, and the best signal result from people they follow. Goes to the bell, and to push or email
 * as they chose for the "Daily digest" category. Nothing is sent on a day with nothing to say.
 */
@Slf4j
@Service
public class DigestService {

    private final JdbcTemplate jdbc;
    private final NotificationService notifications;
    private final ProfileMoneyController money;

    public DigestService(JdbcTemplate jdbc, NotificationService notifications, ProfileMoneyController money) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.money = money;
    }

    record Due(long userId, String tz, int hour, LocalDate last) {}

    @Scheduled(cron = "${finmates.digest.cron:0 5 * * * *}")
    public void run() {
        List<Due> due = jdbc.query("SELECT user_id, time_zone, digest_hour, last_digest_on FROM notification_prefs WHERE digest",
                (rs, i) -> new Due(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getObject(4, LocalDate.class)));
        int sent = 0;
        for (Due d : due) {
            ZoneId z;
            try { z = d.tz() == null ? ZoneId.of("America/New_York") : ZoneId.of(d.tz()); } catch (Exception e) { z = ZoneOffset.UTC; }
            ZonedDateTime now = ZonedDateTime.now(z);
            if (now.getHour() != d.hour() || now.toLocalDate().equals(d.last())) continue;
            try {
                if (send(d.userId(), now.toLocalDate())) sent++;
            } catch (Exception e) {
                log.warn("event=digest_failed user={} error={}", d.userId(), e.toString());
            }
            jdbc.update("UPDATE notification_prefs SET last_digest_on = ? WHERE user_id = ?", now.toLocalDate(), d.userId());
        }
        if (sent > 0) log.info("event=digest_sent count={}", sent);
    }

    /** Builds and stores the digest. Returns false when there was nothing worth saying. */
    boolean send(long userId, LocalDate day) {
        List<String> lines = new ArrayList<>();

        // 1. You against your mates, 30 days (each mate's privacy applied, as on the dashboard).
        try {
            var mm = money.matesMoneyFor(userId, false);
            if (mm.me() != null && mm.me().return30dPct() != null) {
                BigDecimal mine = mm.me().return30dPct();
                List<BigDecimal> rets = new ArrayList<>(mm.mates().stream().map(ProfileMoneyController.MateMoney::return30dPct)
                        .filter(java.util.Objects::nonNull).toList());
                rets.add(mine);
                rets.sort(Comparator.reverseOrder());
                String line = "Your 30 days: " + pct(mine);
                if (rets.size() > 1) line += ", #" + (rets.indexOf(mine) + 1) + " of " + rets.size() + " with your mates";
                lines.add(line + ".");
            }
        } catch (Exception e) {
            log.debug("digest money unavailable user={} error={}", userId, e.toString());
        }

        // 2. What fired in the last day.
        Integer alerts = jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND type = 'PRICE_ALERT' AND created_at > now() - interval '1 day'", Integer.class, userId);
        Integer outOfRange = jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND type = 'LP_OUT_OF_RANGE' AND created_at > now() - interval '1 day'", Integer.class, userId);
        if (alerts != null && alerts > 0) lines.add(alerts + " price alert" + (alerts == 1 ? "" : "s") + " fired.");
        if (outOfRange != null && outOfRange > 0) lines.add(outOfRange + " of your liquidity positions went out of range.");

        // 3. People you follow: new signals and the best result.
        Integer posted = jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND type = 'SIGNAL_POSTED' AND created_at > now() - interval '1 day'", Integer.class, userId);
        List<String> hits = jdbc.queryForList("SELECT title FROM notifications WHERE user_id = ? AND type = 'SIGNAL_HIT' AND actor_id IS NOT NULL AND created_at > now() - interval '1 day' ORDER BY created_at DESC LIMIT 20", String.class, userId);
        String best = hits.stream().max(Comparator.comparing(DigestService::resultOf)).orElse(null);
        if (posted != null && posted > 0) lines.add(posted + " new signal" + (posted == 1 ? "" : "s") + " from people you follow.");
        if (best != null) lines.add("Best result: " + best + ".");

        Integer unread = jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND read_at IS NULL AND type LIKE 'MESSAGE%'", Integer.class, userId);
        if (unread != null && unread > 0) lines.add(unread + " unread message" + (unread == 1 ? "" : "s") + ".");

        if (lines.isEmpty()) return false;
        notifications.notify(userId, "DIGEST", null, "Your Finmates morning, " + day.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US) + " " + day.getDayOfMonth(),
                String.join("\n", lines), "/dashboard");
        return true;
    }

    /** "+12.4%" in a signal title, for picking the best one; 0 when none. */
    static double resultOf(String title) {
        var m = java.util.regex.Pattern.compile("\\(([+−-]?)(\\d+(?:\\.\\d+)?)%\\)").matcher(title == null ? "" : title);
        if (!m.find()) return 0;
        double v = Double.parseDouble(m.group(2));
        return m.group(1).equals("−") || m.group(1).equals("-") ? -v : v;
    }

    private static String pct(BigDecimal v) {
        return (v.signum() >= 0 ? "+" : "−") + v.abs().setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
    }
}
