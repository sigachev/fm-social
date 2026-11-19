package com.finmates.social.profile;

public enum PermissionLevel {
    PUBLIC,
    @com.fasterxml.jackson.annotation.JsonAlias("FOLLOWERS") MATES,
    DISABLED
}
