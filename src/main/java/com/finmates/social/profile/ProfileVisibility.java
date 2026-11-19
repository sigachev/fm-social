package com.finmates.social.profile;

/** Everyone / Mates (people the owner follows back) / Only me. FOLLOWERS is read as MATES for older clients. */
public enum ProfileVisibility {
    PUBLIC,
    @com.fasterxml.jackson.annotation.JsonAlias("FOLLOWERS") MATES,
    PRIVATE
}
