package com.finmates.social.edit;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "comment_edits")
public class CommentEdit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "comment_id", nullable = false)
    private Long commentId;

    @Column(name = "previous_content", nullable = false, columnDefinition = "TEXT")
    private String previousContent;

    @Column(name = "edited_at", nullable = false, updatable = false)
    private OffsetDateTime editedAt;

    @PrePersist
    protected void onCreate() {
        if (editedAt == null) editedAt = OffsetDateTime.now();
    }
}
