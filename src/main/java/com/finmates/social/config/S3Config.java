package com.finmates.social.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Slf4j
@Configuration
public class S3Config {

    @Value("${fm-social.s3.region:us-east-1}")
    private String awsRegion;

    @Value("${fm-social.s3.bucket:finmates-media}")
    private String bucket;

    @Bean
    public S3Client s3Client() {
        S3Client client = S3Client.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
        log.info("S3Client configured for region={}, bucket={}", awsRegion, bucket);
        return client;
    }

    @Bean
    public S3Presigner s3Presigner() {
        return S3Presigner.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
