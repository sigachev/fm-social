package com.finmates.social.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

@Configuration
public class WebClientConfig {

    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(3);

    @Value("${finmates.services.main-url}")
    private String mainUrl;

    @Value("${finmates.services.crypto-url}")
    private String cryptoUrl;

    @Value("${finmates.internal.shared-secret}")
    private String sharedSecret;

    /**
     * WebClient for calling finmates-main internal endpoints.
     * Adds X-Internal-Secret header for service-to-service auth.
     */
    @Bean
    public WebClient mainServiceWebClient() {
        return WebClient.builder()
                .baseUrl(mainUrl)
                .defaultHeader("X-Internal-Secret", sharedSecret)
                .filter((request, next) -> next.exchange(request)
                        .timeout(RESPONSE_TIMEOUT))
                .build();
    }

    /**
     * WebClient for calling finmates-crypto internal endpoints.
     * Adds X-Internal-Secret header for service-to-service auth.
     */
    @Bean
    public WebClient cryptoServiceWebClient() {
        return WebClient.builder()
                .baseUrl(cryptoUrl)
                .defaultHeader("X-Internal-Secret", sharedSecret)
                .filter((request, next) -> next.exchange(request)
                        .timeout(RESPONSE_TIMEOUT))
                .build();
    }
}
