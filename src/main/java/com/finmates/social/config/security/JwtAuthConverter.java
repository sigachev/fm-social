package com.finmates.social.config.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Converts a Keycloak JWT into a Spring Security {@link JwtAuthenticationToken}.
 * Extracts roles from {@code resource_access.<resourceId>.roles} and falls back
 * to {@code realm_access.roles}, filtering out Keycloak system roles.
 * Mirrors the pattern in finmates-main JwtAuthConverter.
 */
@Component
public class JwtAuthConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final JwtGrantedAuthoritiesConverter jwtGrantedAuthoritiesConverter =
            new JwtGrantedAuthoritiesConverter();

    private final JwtAuthConverterProperties properties;

    public JwtAuthConverter(JwtAuthConverterProperties properties) {
        this.properties = properties;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = Stream.concat(
                jwtGrantedAuthoritiesConverter.convert(jwt).stream(),
                extractResourceRoles(jwt).stream()
        ).collect(Collectors.toSet());

        return new JwtAuthenticationToken(jwt, authorities, getPrincipalClaimName(jwt));
    }

    private String getPrincipalClaimName(Jwt jwt) {
        return jwt.getClaim(properties.getPrincipalAttribute());
    }

    private Collection<? extends GrantedAuthority> extractResourceRoles(Jwt jwt) {
        Set<GrantedAuthority> authorities = new HashSet<>();

        // Extract roles from resource_access.<resourceId>.roles
        Map<String, Object> resourceAccess = jwt.getClaim("resource_access");
        if (resourceAccess != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> resource = (Map<String, Object>) resourceAccess.get(properties.getResourceId());
            if (resource != null) {
                @SuppressWarnings("unchecked")
                Collection<String> resourceRoles = (Collection<String>) resource.get("roles");
                if (resourceRoles != null) {
                    resourceRoles.stream()
                            .map(this::mapToGrantedAuthority)
                            .forEach(authorities::add);
                }
            }
        }

        // Fallback: extract from realm_access.roles, filtering Keycloak system roles
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess != null) {
            @SuppressWarnings("unchecked")
            Collection<String> realmRoles = (Collection<String>) realmAccess.get("roles");
            if (realmRoles != null) {
                realmRoles.stream()
                        .filter(role -> !role.startsWith("default-roles-")
                                && !role.equals("offline_access")
                                && !role.equals("uma_authorization"))
                        .map(this::mapToGrantedAuthority)
                        .forEach(authorities::add);
            }
        }

        // Ensure every authenticated user has at least ROLE_USER
        boolean hasUserRole = authorities.stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_USER"));
        if (!hasUserRole) {
            authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        }

        return authorities;
    }

    private GrantedAuthority mapToGrantedAuthority(String role) {
        String clean = role;
        if (clean.endsWith("_ROLE"))   clean = clean.substring(0, clean.length() - 5);
        if (clean.startsWith("ROLE_")) clean = clean.substring(5);
        return new SimpleGrantedAuthority("ROLE_" + clean.toUpperCase());
    }
}
