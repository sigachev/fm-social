package com.finmates.social.util;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts cashtags from user-authored content (comments today, possibly posts later).
 *
 * <p>MUST stay in sync with the FE counterpart at:
 * <pre>F:\Projects\finmates-front\src\components\mentions\mentionSegments.ts:18</pre>
 * and with the SQL backfill pattern in:
 * <pre>V17__cashtag_extraction.sql</pre>
 * If any of the three regex literals changes, all three must change atomically
 * and the manual FE/BE cross-check documented in {@code CashtagExtractorTest}
 * must be re-run.</p>
 *
 * <h2>Behavior</h2>
 * <ul>
 *   <li>Procedural word-boundary via lookbehind: {@code $} must not be preceded
 *       by an alphanumeric character (so {@code "foo$BAR"} does not match).</li>
 *   <li>Symbol shape: first char is a letter, then 0–14 alphanumerics (1–15
 *       total). This intentionally excludes digit-prefix tickers like
 *       {@code 1INCH} or {@code 00} — see "Known asymmetry" in
 *       {@code fm-social/CLAUDE.md}. Tracked for a post-launch follow-up.</li>
 *   <li>Accepts mixed-case input ({@code $eth}, {@code $Eth}, {@code $ETH});
 *       canonicalises every match to uppercase.</li>
 *   <li>Returns a sorted, deduplicated {@link List}. Sort order matches the
 *       backfill SQL ({@code ORDER BY UPPER(m[1])}) for deterministic
 *       FE/BE/DB equivalence.</li>
 *   <li>{@code null} or empty input returns an empty (never {@code null})
 *       list — callers can {@code setExtractedCashtags(...)} unconditionally.</li>
 * </ul>
 *
 * <h2>Why it's a static utility, not a Spring bean</h2>
 * <p>Pure function with no I/O, no configuration, and no collaborators. Keeping
 * it as a stateless utility lets unit tests run without any Spring context and
 * makes the call sites in {@code CommentService} trivially readable.</p>
 */
public final class CashtagExtractor {

    /**
     * Cashtag pattern. Compiled once at class-load — {@link Pattern} is
     * thread-safe and is reused via per-thread {@link Matcher} instances.
     *
     * <p>The lookbehind {@code (?<![A-Za-z0-9])} is the Java/Postgres analogue
     * of the FE {@code collectSegments()} procedural check
     * {@code isAlphaNumeric(text[start - 1])} at
     * {@code mentionSegments.ts:38}.</p>
     */
    private static final Pattern CASHTAG_PATTERN =
            Pattern.compile("(?<![A-Za-z0-9])\\$([A-Za-z][A-Za-z0-9]{0,14})");

    private CashtagExtractor() {
        // no instances
    }

    /**
     * Extract canonical (uppercase, sorted, deduplicated) cashtags from
     * {@code content}.
     *
     * @param content the raw comment / post body; may be {@code null}
     * @return a new mutable {@link List} of canonical symbols; never {@code null}
     */
    public static List<String> extract(String content) {
        if (content == null || content.isEmpty()) {
            return new ArrayList<>();
        }
        // TreeSet gives sort + dedup in one structure; final ArrayList copy
        // gives callers a mutable List they can hand straight to JPA without
        // worrying about a fixed-size or unmodifiable view.
        TreeSet<String> seen = new TreeSet<>();
        Matcher m = CASHTAG_PATTERN.matcher(content);
        while (m.find()) {
            seen.add(m.group(1).toUpperCase());
        }
        return new ArrayList<>(seen);
    }
}
