package com.finmates.social.notification.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Browser notifications that arrive with the Finmates tab closed (Web Push). Each browser that
 * allows notifications registers a subscription; a notification for its user is encrypted for that
 * browser and handed to the browser vendor's push service. Expired subscriptions (404/410) are removed.
 *
 * <p>Needs a VAPID key pair in {@code WEBPUSH_PUBLIC_KEY} / {@code WEBPUSH_PRIVATE_KEY} (base64url;
 * generate with {@code java scripts/VapidKeys.java}). Without it push is simply off.
 */
@Slf4j
@Service
public class PushService {

    public record Subscription(long id, long userId, String endpoint, String p256dh, String auth) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String publicKeyB64;
    private final byte[] publicKey;
    private final PrivateKey privateKey;
    private final String subject;

    public PushService(JdbcTemplate jdbc, ObjectMapper json,
                       @Value("${finmates.webpush.public-key:}") String pub,
                       @Value("${finmates.webpush.private-key:}") String priv,
                       @Value("${finmates.webpush.subject:mailto:admin@finmates.com}") String subject) {
        this.jdbc = jdbc;
        this.json = json;
        this.subject = subject;
        byte[] pk = null;
        PrivateKey sk = null;
        try {
            if (!pub.isBlank() && !priv.isBlank()) {
                pk = WebPush.b64(pub);
                WebPush.publicKey(pk);              // validates
                sk = WebPush.privateKey(WebPush.b64(priv));
            }
        } catch (Exception e) {
            log.warn("event=webpush_keys_invalid error={}", e.toString());
            pk = null;
            sk = null;
        }
        this.publicKey = pk;
        this.privateKey = sk;
        this.publicKeyB64 = pk == null ? null : WebPush.b64(pk);
        if (pk == null) log.info("event=webpush_off reason=no_vapid_keys");
    }

    public boolean configured() {
        return privateKey != null;
    }

    public String publicKey() {
        return publicKeyB64;
    }

    public void subscribe(long userId, String endpoint, String p256dh, String auth, String userAgent) {
        if (endpoint == null || !endpoint.startsWith("https://") || p256dh == null || auth == null) {
            throw new IllegalArgumentException("Incomplete subscription.");
        }
        jdbc.update("""
                INSERT INTO push_subscriptions (user_id, endpoint, p256dh, auth, user_agent) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (endpoint) DO UPDATE SET user_id = EXCLUDED.user_id, p256dh = EXCLUDED.p256dh,
                    auth = EXCLUDED.auth, user_agent = EXCLUDED.user_agent""",
                userId, endpoint, p256dh, auth, userAgent == null ? null : userAgent.substring(0, Math.min(200, userAgent.length())));
        // At most 10 browsers per person: drop the oldest.
        jdbc.update("""
                DELETE FROM push_subscriptions WHERE user_id = ? AND id NOT IN
                    (SELECT id FROM push_subscriptions WHERE user_id = ? ORDER BY created_at DESC LIMIT 10)""", userId, userId);
    }

    public void unsubscribe(long userId, String endpoint) {
        jdbc.update("DELETE FROM push_subscriptions WHERE user_id = ? AND endpoint = ?", userId, endpoint);
    }

    public int devices(long userId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM push_subscriptions WHERE user_id = ?", Integer.class, userId);
        return n == null ? 0 : n;
    }

    /** Sends to every browser of the user. Returns how many accepted it. */
    public int send(long userId, String title, String body, String link, String tag, boolean urgent) {
        if (!configured()) return 0;
        List<Subscription> subs = jdbc.query("SELECT id, user_id, endpoint, p256dh, auth FROM push_subscriptions WHERE user_id = ?",
                (rs, i) -> new Subscription(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5)), userId);
        int ok = 0;
        for (Subscription s : subs) if (sendOne(s, title, body, link, tag, urgent)) ok++;
        return ok;
    }

    private boolean sendOne(Subscription s, String title, String body, String link, String tag, boolean urgent) {
        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("title", title);
            if (body != null) msg.put("body", body);
            msg.put("link", link == null ? "/" : link);
            if (tag != null) msg.put("tag", tag);
            byte[] payload = json.writeValueAsBytes(msg);
            if (payload.length > 3000) {           // stay well under the 4 KB record
                msg.put("body", body == null ? null : body.substring(0, Math.min(body.length(), 300)));
                payload = json.writeValueAsBytes(msg);
            }
            byte[] enc = WebPush.encrypt(payload, WebPush.b64(s.p256dh()), WebPush.b64(s.auth()));
            HttpRequest req = HttpRequest.newBuilder(URI.create(s.endpoint()))
                    .timeout(Duration.ofSeconds(10))
                    .header("TTL", "86400")
                    .header("Urgency", urgent ? "high" : "normal")
                    .header("Content-Encoding", "aes128gcm")
                    .header("Content-Type", "application/octet-stream")
                    .header("Authorization", WebPush.vapid(s.endpoint(), subject, privateKey, publicKey, Instant.now().getEpochSecond()))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(enc))
                    .build();
            HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
            int code = res.statusCode();
            if (code == 404 || code == 410) {
                jdbc.update("DELETE FROM push_subscriptions WHERE id = ?", s.id());
                return false;
            }
            if (code >= 200 && code < 300) {
                jdbc.update("UPDATE push_subscriptions SET last_ok_at = now() WHERE id = ?", s.id());
                return true;
            }
            log.warn("event=webpush_rejected user={} status={}", s.userId(), code);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("event=webpush_failed user={} error={}", s.userId(), e.toString());
        }
        return false;
    }
}
