package com.finmates.social.post;

/** Everyone / Mates (people the owner follows back) / Only me. FOLLOWERS is read as MATES for older clients. */
public enum PostVisibility {
    PUBLIC,
    @com.fasterxml.jackson.annotation.JsonAlias("FOLLOWERS") MATES,
    PRIVATE
}
