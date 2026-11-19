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

    public SocialNotifier(NotificationService notifications, CommentRepository comments, PostRepository posts,
                          BlockRepository blocks, UserLookupCache users) {
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

            if (c.getParentId() != null) {
                comments.findById(c.getParentId()).ifPresent(parent -> {
                    if (tell(parent.getAuthorId(), c.getAuthorId(), told)) {
                        notifications.notify(parent.getAuthorId(), "COMMENT_REPLY", c.getAuthorId(), name + " replied to your comment", snippet, link);
                    }
                });
            } else if (c.getTargetType() == CommentTargetType.POST && c.getTargetId() != null) {
                posts.findById(c.getTargetId()).ifPresent(p -> {
                    if (tell(p.getAuthorId(), c.getAuthorId(), told)) {
                        notifications.notify(p.getAuthorId(), "COMMENT_ON_POST", c.getAuthorId(), name + " commented on your post", snippet, link);
                    }
                });
            }
            mentions(c.getContent(), c.getAuthorId(), told, name + " mentioned you in a comment", snippet, link);
        } catch (Exception e) {
            log.warn("event=comment_notify_failed comment={} error={}", c.getId(), e.toString());
        }
    }

    public void postCreated(Post p) {
        try {
            Set<Long> told = new LinkedHashSet<>();
            told.add(p.getAuthorId());
            mentions(p.getContent(), p.getAuthorId(), told, notifications.nameOf(p.getAuthorId()) + " mentioned you in a post",
                    snippet(p.getContent()), "/post/" + p.getId());
        } catch (Exception e) {
            log.warn("event=post_notify_failed post={} error={}", p.getId(), e.toString());
        }
    }

    private void mentions(String text, Long actorId, Set<Long> told, String title, String body, String link) {
        if (text == null) return;
        Matcher m = MENTION.matcher(text);
        Set<String> names = new LinkedHashSet<>();
        while (m.find() && names.size() < MAX_MENTIONS) names.add(m.group(1).toLowerCase(Locale.ROOT));
        for (String u : names) {
            users.getByUsername(u).map(UserLookupCache.UserSummary::userId).ifPresent(id -> {
                if (tell(id, actorId, told)) notifications.notify(id, "MENTION", actorId, title, body, link);
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

    private String linkFor(Comment c) {
        if (c.getTargetType() == CommentTargetType.POST && c.getTargetId() != null) return "/post/" + c.getTargetId() + "#comment-" + c.getId();
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
