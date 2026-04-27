package com.finmates.social.connections;

/**
 * Visibility scopes consulted by {@link ConnectionsPermissionService}.
 *
 * <p>The scope is the <em>thing being viewed</em>. The owning user's privacy
 * settings (currently {@code Profile.isPrivate}) determine whether non-mate
 * viewers can see content at each scope.</p>
 *
 * <ul>
 *   <li>{@link #PROFILE_BASIC} — always visible. Required so a non-mate can
 *       render the user card and request to follow a private profile.</li>
 *   <li>{@link #FOLLOW_LISTS} — the user's following / followers lists.</li>
 *   <li>{@link #FEED} — the user's posts and activity stream.</li>
 *   <li>{@link #PORTFOLIO} — portfolio context displayed inside the profile
 *       (cross-service fetch via {@code cryptoServiceWebClient}).</li>
 * </ul>
 *
 * <p>Existing field-level filtering driven by {@code Profile.profileVisibility}
 * (PUBLIC / FOLLOWERS / PRIVATE) and the V9 boolean flags is <em>not</em>
 * replaced by these scopes — see {@code follow-feature-notes.md} ("Existing
 * privacy logic to consolidate").</p>
 */
public enum Scope {
    PROFILE_BASIC,
    FOLLOW_LISTS,
    FEED,
    PORTFOLIO
}
