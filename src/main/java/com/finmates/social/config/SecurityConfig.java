package com.finmates.social.config;

import com.finmates.social.config.security.AzpAllowlistValidator;
import com.finmates.social.config.security.JwtAuthConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import com.finmates.social.config.security.AzpReportOnlyFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestTemplate;


import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/actuator/health",
            "/actuator/info",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/api/profiles/*/public",
            "/api/profiles/*/money",
            "/api/profiles/*/positions/*/*",
            "/api/internal/**",
            // Mixed-auth endpoint: anonymous viewers get scope=global, authenticated
            // viewers get scope=network. Method-level @PreAuthorize("permitAll()")
            // on PostController.getPostsByCashtag overrides the class-level
            // @PreAuthorize("isAuthenticated()"); the controller enforces auth
            // internally for scope=network via AuthenticatedUser.currentUserIdOrNull().
            "/api/posts/by-cashtag",
            // Token detail Discussion panel — Mentions tab is mixed-auth, same
            // pattern as /api/posts/by-cashtag. Anonymous viewers on a public
            // token page see Mentions without a JWT.
            //
            // We use the broad `/api/discussion/**` rather than a tighter
            // `/api/discussion/token/*/mentions` because single-segment `*`
            // wildcards in Spring Security 6's MvcRequestMatcher have
            // surfaced unauthenticated 401s in practice (smoke test against
            // the running service) even when the pattern looks syntactically
            // correct. Broader `**` matches the swagger / v3-api-docs entries
            // in this same list and is robust across MvcRequestMatcher /
            // AntPathRequestMatcher selection.
            //
            // Safety: future write endpoints on DiscussionController are NOT
            // exposed to anonymous traffic by this entry. The URL list only
            // controls filter-chain entry; method-level
            // @PreAuthorize("isAuthenticated()") at the class level continues
            // to gate handler invocation. Each read endpoint that wants
            // anonymous access must opt in explicitly with
            // @PreAuthorize("permitAll()") on the method.
            "/api/discussion/**"
    };

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    /** Comma-separated azp allowlist. See CLAUDE.md "JWT azp guard". */
    @Value("${finmates.security.azp.allowed:finmates-app}")
    private List<String> azpAllowed;

    /** false = report-only (log, never reject) for staged rollout. */
    @Value("${finmates.security.azp.enforce:true}")
    private boolean azpEnforce;

    /**
     * Optional ADDITIONAL truststore for the JWKS fetch (escape hatch; in-cluster the image's cacerts already
     * contains the pinned Keycloak cert). Unset = default JVM truststore. When set, its certificates are added to
     * the default JVM trust anchors; full chain + host name verification; this is NOT trust-all.
     */
    @Value("${finmates.security.jwks.trust-store:}")
    private String jwksTrustStore;

    @Value("${finmates.security.jwks.trust-store-password:}")
    private String jwksTrustStorePassword;

    @Value("${finmates.security.jwks.trust-store-type:PKCS12}")
    private String jwksTrustStoreType;

    private final JwtAuthConverter jwtAuthConverter;
    private final InternalSecretFilter internalSecretFilter;
    private final ProfileEnsureFilter profileEnsureFilter;

    public SecurityConfig(JwtAuthConverter jwtAuthConverter,
                          InternalSecretFilter internalSecretFilter,
                          ProfileEnsureFilter profileEnsureFilter) {
        this.jwtAuthConverter = jwtAuthConverter;
        this.internalSecretFilter = internalSecretFilter;
        this.profileEnsureFilter = profileEnsureFilter;
    }

    private static final String JWKS_PATH = "/protocol/openid-connect/certs";

    /**
     * JwtDecoder for every profile EXCEPT {@code dev}: the JWKS is fetched with normal TLS
     * verification (default JVM truststore, plus the certificates of the optional {@code finmates.security.jwks.trust-store}),
     * including host name verification. Never trust-all here.
     */
    @Bean
    @Profile("!dev")
    public JwtDecoder jwtDecoder() throws Exception {
        return verifiedJwtDecoder(issuerUri + JWKS_PATH, issuerUri, azpAllowed, azpEnforce,
                jwksTrustStore, jwksTrustStorePassword, jwksTrustStoreType);
    }

    /**
     * {@code dev} profile ONLY: trust-all JWKS fetch for a local Keycloak with a self-signed
     * certificate. Logs a loud WARN at startup.
     */
    @Bean
    @Profile("dev")
    public JwtDecoder devTrustAllJwtDecoder() throws Exception {
        return trustAllJwtDecoder(issuerUri + JWKS_PATH, issuerUri, azpAllowed, azpEnforce);
    }

    /** dev-only composition (TLS verification disabled). */
    static NimbusJwtDecoder trustAllJwtDecoder(String jwkSetUri, String issuerUri, List<String> azpAllowed,
                                               boolean azpEnforce) throws Exception {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                .restOperations(JwksTlsSupport.trustAllRestTemplateForDev())
                .build();
        return withTokenValidators(decoder, issuerUri, azpAllowed, azpEnforce);
    }

    /** Production composition for non-dev profiles (static so tests build it exactly as production does). */
    static NimbusJwtDecoder verifiedJwtDecoder(String jwkSetUri, String issuerUri, List<String> azpAllowed,
                                               boolean azpEnforce, String trustStore, String trustStorePassword,
                                               String trustStoreType) throws Exception {
        RestTemplate restTemplate = JwksTlsSupport.verifiedRestTemplate(trustStore, trustStorePassword, trustStoreType);
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                .restOperations(restTemplate)
                .build();
        return withTokenValidators(decoder, issuerUri, azpAllowed, azpEnforce);
    }

    /**
     * Applies the full validator chain to a decoder: the default validators (timestamps) + issuer
     * check, plus the azp allowlist guard. Static so the wiring can be tested without a JWKS endpoint.
     */
    static NimbusJwtDecoder withTokenValidators(NimbusJwtDecoder decoder, String issuerUri,
                                                List<String> azpAllowed, boolean azpEnforce) {
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuerUri),
                new AzpAllowlistValidator(azpAllowed, azpEnforce));
        decoder.setJwtValidator(validator);
        return decoder;
    }

    /**
     * Report-only azp logging (service, method, normalised endpoint, azp - never the token). Order -50 runs
     * after Spring Security (-100) so the JWT is already authenticated. Uses the same allowlist/enforce
     * values as the decoder; a no-op when enforcement is on. See CLAUDE.md "JWT azp guard".
     */
    @Bean
    public FilterRegistrationBean<AzpReportOnlyFilter> azpReportOnlyFilter(
            @Value("${spring.application.name:fm-social}") String serviceName) {
        FilterRegistrationBean<AzpReportOnlyFilter> registration = new FilterRegistrationBean<>(
                new AzpReportOnlyFilter(serviceName, azpAllowed, azpEnforce));
        registration.setOrder(-50);
        return registration;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.cors(Customizer.withDefaults())
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(PUBLIC_PATHS).permitAll()
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter))
            )
            .addFilterBefore(internalSecretFilter,
                    org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(profileEnsureFilter,
                    org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class);

        return http.build();
    }
}
