package com.finmates.social.profile;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfileRepository extends JpaRepository<Profile, Long> {
    // user_id is the PK, so findById(userId) and existsById(userId) are inherited
}
