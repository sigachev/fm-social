package com.finmates.social.edit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CommentEditRepository extends JpaRepository<CommentEdit, Long> {

    @Query("SELECT ce FROM CommentEdit ce WHERE ce.commentId = :commentId ORDER BY ce.editedAt DESC")
    List<CommentEdit> findByCommentIdOrderByEditedAtDesc(@Param("commentId") Long commentId);
}
