package com.finmates.social.follow;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * A directional follow relationship between two users.
 *
 * <p>Status semantics:</p>
 * <ul>
 *   <li>{@link FollowStatus#ACTIVE} — established follow. Counts toward feed fan-out,
 *       follower/following counts, and mate detection.</li>
 *   <li>{@link FollowStatus#PENDING} — request awaiting approval against a private profile.
 *       Does NOT count toward feed fan-out or public counts; surfaced only to the followee
 *       in their pending-incoming list and to the follower in their pending-outgoing list.</li>
 * </ul>
 *
 * <p>The {@code status} column (VARCHAR(20) with CHECK constraint) is created by V16.
 * Until V16 lands the entity will fail Hibernate {@code ddl-auto=validate} startup — this
 * is intentional and accepted as part of the staged rollout. Storage matches the existing
 * fm-social convention for status/visibility enums on {@code Profile} (VARCHAR + STRING enum)
 * rather than a native PG enum type.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "follows")
public class Follow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "follower_id", nullable = false)
    private Long followerId;

    @Column(name = "followed_id", nullable = false)
    private Long followedId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private FollowStatus status = FollowStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (status == null) status = FollowStatus.ACTIVE;
    }
}
