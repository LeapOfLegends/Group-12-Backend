package group12.marketdata;

import group12.Entities.MarketCode;
import group12.Entities.MarketStatus;
import group12.Services.MarketService;
import group12.marketdata.alpaca.AlpacaMarketStatusClient;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketStatusRefreshServiceTest {

    @Test
    void openClockPersistsOpenIexAndOpenCryptoWithSameTimestamp() {
        MarketService marketService = mock(MarketService.class);
        AlpacaMarketStatusClient statusClient = mock(AlpacaMarketStatusClient.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-12T09:30:00Z"), ZoneOffset.UTC);
        when(statusClient.isIexOpen()).thenReturn(true);
        MarketStatusRefreshService service =
                new MarketStatusRefreshService(marketService, statusClient, clock);

        service.refreshFromAlpacaClock();

        OffsetDateTime expected = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        verify(marketService).updateMarketStatusSnapshot(
                eq(MarketStatus.OPEN),
                eq(MarketCode.IEX),
                eq(expected)
        );
        verify(marketService).updateMarketStatusSnapshot(
                eq(MarketStatus.OPEN),
                eq(MarketCode.CRYPTO),
                eq(expected)
        );
    }

    @Test
    void closedClockPersistsClosedIexAndOpenCrypto() {
        MarketService marketService = mock(MarketService.class);
        AlpacaMarketStatusClient statusClient = mock(AlpacaMarketStatusClient.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-12T20:30:00Z"), ZoneOffset.UTC);
        when(statusClient.isIexOpen()).thenReturn(false);
        MarketStatusRefreshService service =
                new MarketStatusRefreshService(marketService, statusClient, clock);

        service.refreshFromAlpacaClock();

        OffsetDateTime expected = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        verify(marketService).updateMarketStatusSnapshot(
                eq(MarketStatus.CLOSED),
                eq(MarketCode.IEX),
                eq(expected)
        );
        verify(marketService).updateMarketStatusSnapshot(
                eq(MarketStatus.OPEN),
                eq(MarketCode.CRYPTO),
                eq(expected)
        );
    }
}
