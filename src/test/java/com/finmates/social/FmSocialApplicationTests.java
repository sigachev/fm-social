package com.finmates.social;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class FmSocialApplicationTests {

    @Test
    void contextLoads() {
        // Verifies the Spring context starts cleanly with all config beans.
        // Requires the 'test' profile with an in-memory or Testcontainers PostgreSQL
        // and a mock/embedded Redis — wire those up when integration tests are added.
    }
}
