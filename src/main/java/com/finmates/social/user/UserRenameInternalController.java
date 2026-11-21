package com.finmates.social.user;

import com.finmates.social.client.UserLookupCache;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Service-to-service (finmates-main, X-Internal-Secret via InternalSecretFilter): a user changed their username.
 * Posts and comments keep the author's username for rendering; this moves them to the new one. Idempotent.
 * Old @mentions in text stay as written; finmates-main resolves the old name to the user for a while.
 */
@Slf4j
@RestController
@Tag(name = "Internal: users")
public class UserRenameInternalController {

    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    public record Rename(String oldUsername, String newUsername) {}

    private final JdbcTemplate jdbc;
    private final UserLookupCache users;

    public UserRenameInternalController(JdbcTemplate jdbc, UserLookupCache users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    @PostMapping("/api/internal/users/{userId}/rename")
    @Operation(summary = "A user's username changed: update their posts and comments, forget cached lookups")
    @Transactional
    public ResponseEntity<?> rename(@PathVariable Long userId, @RequestBody Rename r) {
        if (r == null || r.newUsername() == null || !NAME.matcher(r.newUsername()).matches()
                || (r.oldUsername() != null && !NAME.matcher(r.oldUsername()).matches())) {
            return ResponseEntity.badRequest().body(Map.of("message", "Invalid usernames"));
        }
        int posts = jdbc.update("UPDATE posts SET author_username = ? WHERE author_id = ? AND author_username IS DISTINCT FROM ?",
                r.newUsername(), userId, r.newUsername());
        int comments = jdbc.update("UPDATE comments SET author_username = ? WHERE author_id = ? AND author_username <> ?",
                r.newUsername(), userId, r.newUsername());
        users.invalidate(userId, r.oldUsername(), r.newUsername());
        log.info("event=user_renamed user={} posts={} comments={}", userId, posts, comments);
        return ResponseEntity.ok(Map.of("posts", posts, "comments", comments));
    }
}
