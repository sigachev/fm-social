package com.finmates.social.config.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "finmates.jwt")
@Getter
@Setter
public class JwtAuthConverterProperties {

    /** JWT claim used as the principal name. Typically "preferred_username" or "email". */
    private String principalAttribute = "preferred_username";

    /** Keycloak client ID whose resource_access roles to extract. */
    private String resourceId = "finmates-app";
}
