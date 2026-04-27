package com.finmates.social.follow;

/**
 * Lifecycle state of a {@link Follow} row.
 *
 * <p>Backed by the PostgreSQL enum type {@code follow_status} created in
 * {@code V16__follow_status_and_privacy.sql}. Persisted via Hibernate's
 * {@code PostgreSQLEnumJdbcType}, which maps Java enum names ({@code ACTIVE},
 * {@code PENDING}) to the matching PG enum labels ({@code 'active'}, {@code 'pending'}).</p>
 *
 * <p><b>Why no {@code BLOCKED} value:</b> blocking is handled by the dedicated
 * {@code blocks} table and {@link com.finmates.social.block.BlockRepository}.
 * Overloading status with a third state would force every follow query to
 * choose between filtering and joining, and would diverge from the existing
 * "blocks are separate from follows" model.</p>
 */
public enum FollowStatus {
    /** Follow is established and counted in feeds, mate calculations, and public counts. */
    ACTIVE,
    /** Follow has been requested against a private profile and awaits the followee's approval. */
    PENDING
}
