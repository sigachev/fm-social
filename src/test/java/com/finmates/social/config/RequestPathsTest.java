package com.finmates.social.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RequestPathsTest {

    private static boolean internal(String rawPath) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", rawPath);
        r.setRequestURI(rawPath);
        return RequestPaths.isUnder(r, "api", "internal");
    }

    @Test
    void everySpellingOfTheInternalPrefixIsRecognised() {
        for (String p : new String[]{"/api/internal", "/api/internal/", "/api/internal/x/y", "/api/%69nternal/x",
                "/api/%49NTERNAL/x", "/API/Internal", "//api/internal/x", "/api//internal/x", "/api/./internal/x",
                "/api/x/../internal/x", "/api/x/%2e%2e/internal/x", "/api/internal;a=b/x", "/api;a=b/internal/x",
                "/api/internal%2Fx", "/api/internal%5Cx"}) {
            assertThat(internal(p)).as(p).isTrue();
        }
    }

    @Test
    void otherPathsAreNotInternal() {
        for (String p : new String[]{"/", "/api", "/api/", "/api/internalx", "/api/internalx/y", "/api/quotes/internal",
                "/internal", "/api/x/internal", "/api/%2569nternal/x", "/apix/internal"}) {
            assertThat(internal(p)).as(p).isFalse();
        }
    }

    @Test
    void aContextPathIsNotPartOfTheMatchedPath() {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/svc/api/%69nternal/x");
        r.setContextPath("/svc");
        r.setRequestURI("/svc/api/%69nternal/x");
        assertThat(RequestPaths.isUnder(r, "api", "internal")).isTrue();
    }

    @Test
    void anUnparseablePathFailsClosed() {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/%zz");
        r.setRequestURI("/api/%zz");
        assertThat(RequestPaths.isUnder(r, "api", "internal")).isTrue();
    }
}
