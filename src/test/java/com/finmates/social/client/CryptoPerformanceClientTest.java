package com.finmates.social.client;

import com.finmates.social.feed.dto.TraderPerf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CryptoPerformanceClient}.
 *
 * <p>Mocks at the {@link ExchangeFunction} seam — no Spring context, no
 * MockWebServer dependency (the project doesn't bundle okhttp3-mockwebserver).
 * The exchange function records request count for cache-hit assertions and
 * pulls scripted responses from a queue.
 *
 * <p>Covers the three contracts that matter to downstream consumers:
 * <ul>
 *   <li>cache-hit avoids a second cross-service call within TTL</li>
 *   <li>upstream nulls (insufficient history) propagate as-is — never zeroed</li>
 *   <li>upstream outage returns {@link TraderPerf#EMPTY} and the failure is
 *       cached, so a downed crypto doesn't trigger a retry storm</li>
 * </ul>
 */
class CryptoPerformanceClientTest {

    private final Deque<ClientResponse> scripted = new ArrayDeque<>();
    private final AtomicInteger callCount = new AtomicInteger();
    private CryptoPerformanceClient client;

    @BeforeEach
    void setUp() {
        scripted.clear();
        callCount.set(0);
        ExchangeFunction exchange = req -> {
            callCount.incrementAndGet();
            ClientResponse next = scripted.pollFirst();
            if (next == null) {
                throw new IllegalStateException("Test bug: more requests than scripted responses");
            }
            return Mono.just(next);
        };
        WebClient webClient = WebClient.builder()
                .baseUrl("http://localhost")
                .exchangeFunction(exchange)
                .build();
        client = new CryptoPerformanceClient(webClient);
    }

    private void scriptJson(String body) {
        scripted.add(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build());
    }

    private void scriptStatus(HttpStatus status) {
        scripted.add(ClientResponse.create(status).build());
    }

    @Test
    void cacheHitWithinTtl_noUpstreamCall() {
        scriptJson("{\"return1dPct\":1.85,\"return1wPct\":4.76,\"return1mPct\":10.00}");

        TraderPerf first = client.getRollingReturns(42L);
        TraderPerf second = client.getRollingReturns(42L);  // should hit cache

        assertThat(first.return1dPct()).isEqualByComparingTo("1.85");
        assertThat(second).isSameAs(first); // same instance from cache
        assertThat(callCount.get()).isEqualTo(1);
        assertThat(client.cacheHitCount()).isEqualTo(1);
    }

    @Test
    void cacheExpired_refetchesFromCrypto() {
        scriptJson("{\"return1dPct\":1.00,\"return1wPct\":null,\"return1mPct\":null}");
        scriptJson("{\"return1dPct\":2.00,\"return1wPct\":null,\"return1mPct\":null}");

        TraderPerf first = client.getRollingReturns(7L);
        client.invalidate(7L);                              // simulate TTL expiry
        TraderPerf second = client.getRollingReturns(7L);

        assertThat(first.return1dPct()).isEqualByComparingTo("1.00");
        assertThat(second.return1dPct()).isEqualByComparingTo("2.00");
        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    void upstreamReturnsNullsForInsufficientHistory_traderPerfHasNullFields() {
        // Crypto returns explicit nulls (not zeros) when the user has insufficient
        // history. The contract is: propagate the nulls untouched. This test
        // guards against the "default to BigDecimal.ZERO" anti-pattern that the
        // team explicitly called out as a recurring bug.
        scriptJson("{\"return1dPct\":null,\"return1wPct\":null,\"return1mPct\":null}");

        TraderPerf perf = client.getRollingReturns(99L);

        assertThat(perf.return1dPct()).isNull();
        assertThat(perf.return1wPct()).isNull();
        assertThat(perf.return1mPct()).isNull();
    }

    @Test
    void cryptoServiceDown_returnsEmptySentinelAndCachesIt() {
        scriptStatus(HttpStatus.SERVICE_UNAVAILABLE);

        TraderPerf first = client.getRollingReturns(13L);
        TraderPerf second = client.getRollingReturns(13L);  // cache hit on the EMPTY sentinel

        assertThat(first).isEqualTo(TraderPerf.EMPTY);
        assertThat(second).isEqualTo(TraderPerf.EMPTY);
        assertThat(callCount.get())
                .as("EMPTY result is cached — no retry storm on outage")
                .isEqualTo(1);
    }

    @Test
    void batchEmptyInput_returnsEmptyMap() {
        Map<Long, TraderPerf> result = client.getRollingReturnsBatch(List.of());
        assertThat(result).isEmpty();
        assertThat(callCount.get()).isZero();
    }

    @Test
    void batchAllCacheMisses_oneCallPerUser_resultMapHasEntryForEach() {
        scriptJson("{\"return1dPct\":1.00,\"return1wPct\":null,\"return1mPct\":null}");
        scriptJson("{\"return1dPct\":2.00,\"return1wPct\":null,\"return1mPct\":null}");
        scriptJson("{\"return1dPct\":3.00,\"return1wPct\":null,\"return1mPct\":null}");

        Map<Long, TraderPerf> result = client.getRollingReturnsBatch(List.of(1L, 2L, 3L));

        assertThat(result).containsKeys(1L, 2L, 3L);
        assertThat(callCount.get()).isEqualTo(3);
    }

    @Test
    void batchPartialCacheHit_skipsUpstreamForCachedUsers() {
        scriptJson("{\"return1dPct\":1.00,\"return1wPct\":null,\"return1mPct\":null}");
        client.getRollingReturns(1L);                            // warm cache for user 1
        assertThat(callCount.get()).isEqualTo(1);

        scriptJson("{\"return1dPct\":2.00,\"return1wPct\":null,\"return1mPct\":null}");
        Map<Long, TraderPerf> result = client.getRollingReturnsBatch(List.of(1L, 2L));

        assertThat(result).containsKeys(1L, 2L);
        assertThat(result.get(1L).return1dPct()).isEqualByComparingTo("1.00");
        assertThat(result.get(2L).return1dPct()).isEqualByComparingTo("2.00");
        assertThat(callCount.get())
                .as("Cached users should not trigger a second call")
                .isEqualTo(2);  // 1 warm-up + 1 batch-miss
    }
}
