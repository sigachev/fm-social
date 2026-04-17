package com.finmates.social.edit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PostEditRepository extends JpaRepository<PostEdit, Long> {

    @Query("SELECT pe FROM PostEdit pe WHERE pe.postId = :postId ORDER BY pe.editedAt DESC")
    List<PostEdit> findByPostIdOrderByEditedAtDesc(@Param("postId") Long postId);
}
