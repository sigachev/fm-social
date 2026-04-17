package com.finmates.social.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
public class WebClientConfig {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_S = 10;

    @Value("${finmates.services.main-url}")
    private String mainUrl;

    @Value("${finmates.services.crypto-url}")
    private String cryptoUrl;

    @Value("${finmates.internal.shared-secret}")
    private String sharedSecret;

    private ReactorClientHttpConnector buildConnector() {
        try {
            var sslContext = SslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .build();
            HttpClient httpClient = HttpClient.create()
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                    .responseTimeout(Duration.ofSeconds(READ_TIMEOUT_S))
                    .doOnConnected(conn -> conn
                            .addHandlerLast(new ReadTimeoutHandler(READ_TIMEOUT_S, TimeUnit.SECONDS))
                            .addHandlerLast(new WriteTimeoutHandler(READ_TIMEOUT_S, TimeUnit.SECONDS)))
                    .secure(spec -> spec.sslContext(sslContext));
            return new ReactorClientHttpConnector(httpClient);
        } catch (Exception e) {
            // Fallback to default connector if SSL setup fails
            return new ReactorClientHttpConnector(HttpClient.create()
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                    .responseTimeout(Duration.ofSeconds(READ_TIMEOUT_S)));
        }
    }

    @Bean
    public WebClient mainServiceWebClient() {
        return WebClient.builder()
                .baseUrl(mainUrl)
                .defaultHeader("X-Internal-Secret", sharedSecret)
                .defaultHeader("User-Agent", "fm-social/1.0")
                .clientConnector(buildConnector())
                .build();
    }

    @Bean
    public WebClient cryptoServiceWebClient() {
        return WebClient.builder()
                .baseUrl(cryptoUrl)
                .defaultHeader("X-Internal-Secret", sharedSecret)
                .defaultHeader("User-Agent", "fm-social/1.0")
                .clientConnector(buildConnector())
                .build();
    }
}
