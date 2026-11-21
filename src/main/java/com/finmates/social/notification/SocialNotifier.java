package com.finmates.social.notification;

import com.finmates.social.block.BlockRepository;
import com.finmates.social.client.UserLookupCache;
import com.finmates.social.comment.Comment;
import com.finmates.social.comment.CommentRepository;
import com.finmates.social.comment.CommentTargetType;
import com.finmates.social.post.Post;
import com.finmates.social.post.PostRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Notifications for conversations: a reply to your comment, a comment on your post, and an
 * @mention in a post or comment. One notification per person per event (a reply that also mentions
 * you is a reply), never to yourself, and never across a block. Failures are logged only.
 */
@Slf4j
@Component
public class SocialNotifier {

    static final Pattern MENTION = Pattern.compile("(?<![\\w@])@([A-Za-z0-9_.-]{3,32})");
    private static final int MAX_MENTIONS = 5;

    private final NotificationService notifications;
    private final CommentRepository comments;
    private final PostRepository posts;
    private final BlockRepository blocks;
    private final UserLookupCache users;
    private final com.finmates.social.access.AudienceService audience;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public SocialNotifier(NotificationService notifications, CommentRepository comments, PostRepository posts,
                          BlockRepository blocks, UserLookupCache users, com.finmates.social.access.AudienceService audience,
                          org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.audience = audience;
        this.notifications = notifications;
        this.comments = comments;
        this.posts = posts;
        this.blocks = blocks;
        this.users = users;
    }

    public void commentCreated(Comment c) {
        try {
            Set<Long> told = new LinkedHashSet<>();
            told.add(c.getAuthorId());
            String link = linkFor(c);
            String snippet = snippet(c.getContent());
            String name = notifications.nameOf(c.getAuthorId());
            // Notifications carry a snippet: only people who can see what the comment is on hear about it.
            java.util.function.Predicate<Long> canSee = id -> canSeeTarget(id, c);

            if (c.getParentId() != null) {
                comments.findById(c.getParentId()).ifPresent(parent -> {
                    if (canSee.test(parent.getAuthorId()) && tell(parent.getAuthorId(), c.getAuthorId(), told)) {
                        notifications.notify(parent.getAuthorId(), "COMMENT_REPLY", c.getAuthorId(), name + " replied to your comment", snippet, link);
                    }
                });
            } else if (c.getTargetType() == CommentTargetType.PORTFOLIO && c.getTargetId() != null) {
                // A note on someone's portfolio: tell the owner.
                if (tell(c.getTargetId(), c.getAuthorId(), told)) {
                    notifications.notify(c.getTargetId(), "PORTFOLIO_NOTE", c.getAuthorId(), name + " left a note on your portfolio", snippet, link);
                }
            } else if (c.getTargetType() == CommentTargetType.POST && c.getTargetId() != null) {
                posts.findById(c.getTargetId()).ifPresent(p -> {
                    if (tell(p.getAuthorId(), c.getAuthorId(), told)) {
                        notifications.notify(p.getAuthorId(), "COMMENT_ON_POST", c.getAuthorId(), name + " commented on your post", snippet, link);
                    }
                });
            }
            mentions(c.getContent(), c.getAuthorId(), told, name + " mentioned you in a comment", snippet, link, canSee);
        } catch (Exception e) {
            log.warn("event=comment_notify_failed comment={} error={}", c.getId(), e.toString());
        }
    }

    public void postCreated(Post p) {
        try {
            Set<Long> told = new LinkedHashSet<>();
            told.add(p.getAuthorId());
            mentions(p.getContent(), p.getAuthorId(), told, notifications.nameOf(p.getAuthorId()) + " mentioned you in a post",
                    snippet(p.getContent()), "/post/" + p.getId(), id -> audience.viewer(id).canSee(p));
        } catch (Exception e) {
            log.warn("event=post_notify_failed post={} error={}", p.getId(), e.toString());
        }
    }

    /** Can userId see what this comment is on (its post or portfolio)? Token comments are public. */
    private boolean canSeeTarget(Long userId, com.finmates.social.comment.Comment c) {
        var viewer = audience.viewer(userId);
        if (c.getTargetType() == com.finmates.social.comment.CommentTargetType.POST) {
            return c.getTargetId() != null && posts.findById(c.getTargetId()).map(viewer::canSee).orElse(false);
        }
        if (c.getTargetType() == com.finmates.social.comment.CommentTargetType.PORTFOLIO) {
            return c.getTargetId() != null && viewer.canSeePortfolio(c.getTargetId());
        }
        return true;
    }

    private boolean mentionAllowed(Long actorId, Long mentionedId) {
        var viewer = audience.viewer(actorId);
        return viewer.profile(mentionedId)
                .map(p -> viewer.allows(mentionedId, com.finmates.social.access.AudienceService.of(p.getMentionsPermission())))
                .orElse(false);
    }

    private void mentions(String text, Long actorId, Set<Long> told, String title, String body, String link,
                          java.util.function.Predicate<Long> canSee) {
        if (text == null) return;
        Matcher m = MENTION.matcher(text);
        Set<String> names = new LinkedHashSet<>();
        while (m.find() && names.size() < MAX_MENTIONS) names.add(m.group(1).toLowerCase(Locale.ROOT));
        for (String u : names) {
            users.getByUsername(u).map(UserLookupCache.UserSummary::userId).ifPresent(id -> {
                // "Who can mention you": only people the mentioned user allows notify them.
                if (canSee.test(id) && mentionAllowed(actorId, id) && tell(id, actorId, told)) {
                    notifications.notify(id, "MENTION", actorId, title, body, link);
                }
            });
        }
    }

    /** True (and remembered) when {@code userId} should hear about something {@code actorId} did. */
    private boolean tell(Long userId, Long actorId, Set<Long> told) {
        if (userId == null || told.contains(userId) || Objects.equals(userId, actorId)) return false;
        if (blocks.existsBlockInEitherDirection(userId, actorId)) return false;
        told.add(userId);
        return true;
    }

    /**
     * Someone liked a post or a comment: tell its author, at most once a day per person and item, so liking and
     * unliking can't flood the bell.
     */
    public void reactionAdded(Long actorId, com.finmates.social.reaction.ReactionTargetType type, Long targetId) {
        try {
            Long owner;
            String what, link;
            if (type == com.finmates.social.reaction.ReactionTargetType.POST) {
                var p = posts.findById(targetId).orElse(null);
                if (p == null) return;
                owner = p.getAuthorId();
                what = "your post";
                link = "/post/" + p.getId();
            } else {
                var c = comments.findById(targetId).orElse(null);
                if (c == null) return;
                owner = c.getAuthorId();
                what = "your comment";
                link = linkFor(c);
            }
            if (owner == null || owner.equals(actorId)) return;
            Integer recent = jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = 'REACTION' "
                    + "AND actor_id = ? AND link IS NOT DISTINCT FROM ? AND created_at > NOW() - INTERVAL '1 day'",
                    Integer.class, owner, actorId, link);
            if (recent != null && recent > 0) return;
            notifications.notify(owner, "REACTION", actorId, notifications.nameOf(actorId) + " liked " + what, null, link);
        } catch (Exception e) {
            log.warn("event=reaction_notify_failed target={} error={}", targetId, e.toString());
        }
    }

    private String linkFor(Comment c) {
        if (c.getTargetType() == CommentTargetType.POST && c.getTargetId() != null) return "/post/" + c.getTargetId() + "#comment-" + c.getId();
        if (c.getTargetType() == CommentTargetType.PORTFOLIO && c.getTargetId() != null) return "/profile#comment-" + c.getId();
        if (c.getTargetType() == CommentTargetType.ASSET && c.getTargetSymbol() != null) {
            return "/token/" + c.getTargetSymbol().toLowerCase(Locale.ROOT) + "?comment=" + c.getId();
        }
        return null;
    }

    static String snippet(String s) {
        if (s == null) return null;
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() > 140 ? t.substring(0, 137) + "…" : t;
    }
}
