package group12.marketdata;

import group12.Repository.InstrumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MarketSnapshotPersistenceServiceTest {

    @Mock
    private InstrumentRepository instrumentRepository;

    private MarketSnapshotPersistenceService service;

    @BeforeEach
    void setUp() {
        service = new MarketSnapshotPersistenceService(instrumentRepository);
    }

    @Test
    void quoteOnlyUpdatesQuoteAndPreservesStoredTrade() {
        OffsetDateTime observedAt = OffsetDateTime.parse("2026-09-29T15:00:00Z");
        service.persist(3L, new MarketSnapshot(
                new BigDecimal("10.00"),
                new BigDecimal("10.10"),
                null,
                observedAt,
                null
        ));

        verify(instrumentRepository).updateQuoteSnapshot(
                3L, new BigDecimal("10.00"), new BigDecimal("10.10"), observedAt
        );
        verify(instrumentRepository, never()).updateLastTradeSnapshot(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void tradeOnlyUpdatesTradeAndPreservesStoredQuote() {
        OffsetDateTime observedAt = OffsetDateTime.parse("2026-09-29T15:00:01Z");
        service.persist(3L, new MarketSnapshot(
                null,
                null,
                new BigDecimal("10.05"),
                null,
                observedAt
        ));

        verify(instrumentRepository).updateLastTradeSnapshot(
                3L, new BigDecimal("10.05"), observedAt
        );
        verify(instrumentRepository, never()).updateQuoteSnapshot(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void zeroRowsForOlderOrEqualObservationsAreNotFailures() {
        OffsetDateTime observedAt = OffsetDateTime.parse("2026-09-29T15:00:00Z");
        MarketSnapshot snapshot = new MarketSnapshot(
                new BigDecimal("10.00"),
                new BigDecimal("10.10"),
                new BigDecimal("10.05"),
                observedAt,
                observedAt
        );

        service.persist(3L, snapshot);

        verify(instrumentRepository).updateQuoteSnapshot(
                3L, snapshot.bidPrice(), snapshot.askPrice(), observedAt
        );
        verify(instrumentRepository).updateLastTradeSnapshot(
                3L, snapshot.lastPrice(), observedAt
        );
    }
}
