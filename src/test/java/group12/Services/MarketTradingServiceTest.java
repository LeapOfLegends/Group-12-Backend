package group12.Services;

import group12.Entities.MarketCode;
import group12.Entities.MarketEntity;
import group12.Entities.MarketStatus;
import group12.Entities.OrderRejectionReason;
import group12.Repository.MarketRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketTradingServiceTest {
    private final MarketRepository repository = mock(MarketRepository.class);

    private MarketTradingService service(String instant) {
        return new MarketTradingService(repository, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC),
                Duration.ofSeconds(90));
    }

    private void snapshot(String instant, MarketStatus status) {
        MarketEntity market = new MarketEntity();
        market.setMarketStatus(status);
        market.setStatusAsOf(instant == null ? null : OffsetDateTime.parse(instant));
        when(repository.getMarketEntityForUpdate(MarketCode.IEX)).thenReturn(market);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-02T13:29:59Z", "2026-10-02T20:00:00Z",
            "2026-10-03T15:00:00Z", "2026-10-04T15:00:00Z", "2026-12-04T14:29:59Z",
            "2026-12-04T21:00:00Z"})
    void outsideRegularSession_isClosedWithoutConsultingSnapshot(String instant) {
        var result = service(instant).availability(MarketCode.IEX);
        assertFalse(result.tradingAllowed());
        assertEquals(OrderRejectionReason.MARKET_CLOSED, result.rejectionReason());
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-02T13:30:00Z", "2026-12-04T14:30:00Z"})
    void openingBoundary_handlesDaylightSavingTime(String instant) {
        snapshot(instant, MarketStatus.OPEN);
        assertTrue(service(instant).availability(MarketCode.IEX).tradingAllowed());
    }

    @Test
    void openSnapshot_expiresNoLaterThanRegularSessionClose() {
        snapshot("2026-10-02T19:59:30Z", MarketStatus.OPEN);
        var result = service("2026-10-02T19:59:30Z").availability(MarketCode.IEX);
        assertTrue(result.tradingAllowed());
        assertEquals(Instant.parse("2026-10-02T20:00:00Z"), result.validUntil().toInstant());
    }

    @Test
    void providerClosureDuringRegularHours_blocksHolidaysAndEarlyClosures() {
        snapshot("2026-10-02T15:00:00Z", MarketStatus.CLOSED);
        assertEquals(OrderRejectionReason.MARKET_CLOSED,
                service("2026-10-02T15:00:00Z").availability(MarketCode.IEX).rejectionReason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-02T14:58:30Z", "2026-10-02T14:58:29Z", "2026-10-02T15:00:01Z"})
    void staleOrFutureSnapshot_isUnavailable(String asOf) {
        snapshot(asOf, MarketStatus.OPEN);
        assertEquals(OrderRejectionReason.MARKET_STATUS_UNAVAILABLE,
                service("2026-10-02T15:00:00Z").availability(MarketCode.IEX).rejectionReason());
    }

    @Test
    void missingSnapshotTimestamp_isUnavailable() {
        snapshot(null, MarketStatus.OPEN);
        assertFalse(service("2026-10-02T15:00:00Z").availability(MarketCode.IEX).tradingAllowed());
    }

    @Test
    void missingMarketSnapshotAndUnassignedMarket_failClosed() {
        var service = service("2026-10-02T15:00:00Z");
        assertEquals("UNAVAILABLE", service.availability(MarketCode.LSE).status());
        assertEquals("UNAVAILABLE", service.availability(null).status());
    }

    @Test
    void cryptoIsOpenAtWeekend_withoutProviderStatus() {
        assertTrue(service("2026-10-03T23:00:00Z").availability(MarketCode.CRYPTO).tradingAllowed());
        verifyNoInteractions(repository);
    }
}