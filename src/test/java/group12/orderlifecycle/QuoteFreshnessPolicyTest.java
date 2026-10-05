package group12.orderlifecycle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuoteFreshnessPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-02T15:00:00Z");

    private QuoteFreshnessPolicy policy;

    @BeforeEach
    void setUp() {
        OrderLifecycleProperties properties = new OrderLifecycleProperties();
        properties.getQuote().setMaxAge(Duration.ofSeconds(30));
        policy = new QuoteFreshnessPolicy(
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void quoteAtMaximumAge_isFresh() {
        assertTrue(policy.isFresh(at(NOW.minusSeconds(30))));
    }

    @Test
    void quoteNewerThanMaximumAge_isFresh() {
        assertTrue(policy.isFresh(at(NOW.minusSeconds(29))));
    }

    @Test
    void quoteOlderThanMaximumAge_isStale() {
        assertFalse(policy.isFresh(at(NOW.minusSeconds(31))));
    }

    @Test
    void missingQuoteTimestamp_isNotFresh() {
        assertFalse(policy.isFresh(null));
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
