package group12.marketdata;

import group12.Repository.InstrumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        when(instrumentRepository.updateQuoteSnapshot(
                3L,
                new BigDecimal("10.00"),
                new BigDecimal("10.10"),
                observedAt
        )).thenReturn(1);

        boolean persisted = service.persist(3L, new MarketSnapshot(
                new BigDecimal("10.00"),
                new BigDecimal("10.10"),
                null,
                observedAt,
                null
        ));

        assertTrue(persisted);
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
        when(instrumentRepository.updateLastTradeSnapshot(
                3L,
                new BigDecimal("10.05"),
                observedAt
        )).thenReturn(1);

        boolean persisted = service.persist(3L, new MarketSnapshot(
                null,
                null,
                new BigDecimal("10.05"),
                null,
                observedAt
        ));

        assertTrue(persisted);
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
    void bothObservationWritesAreReportedAsPersisted() {
        OffsetDateTime observedAt = OffsetDateTime.parse("2026-09-29T15:00:00Z");
        MarketSnapshot snapshot = completeSnapshot(observedAt);
        when(instrumentRepository.updateQuoteSnapshot(
                3L, snapshot.bidPrice(), snapshot.askPrice(), observedAt
        )).thenReturn(1);
        when(instrumentRepository.updateLastTradeSnapshot(
                3L, snapshot.lastPrice(), observedAt
        )).thenReturn(1);

        assertTrue(service.persist(3L, snapshot));
    }

    @Test
    void tradeWriteIsReportedWhenQuoteIsRejectedAsOlderOrEqual() {
        OffsetDateTime observedAt = OffsetDateTime.parse("2026-09-29T15:00:00Z");
        MarketSnapshot snapshot = completeSnapshot(observedAt);
        when(instrumentRepository.updateQuoteSnapshot(
                3L, snapshot.bidPrice(), snapshot.askPrice(), observedAt
        )).thenReturn(0);
        when(instrumentRepository.updateLastTradeSnapshot(
                3L, snapshot.lastPrice(), observedAt
        )).thenReturn(1);

        assertTrue(service.persist(3L, snapshot));
    }

    @Test
    void quoteWriteIsReportedWhenTradeIsRejectedAsOlderOrEqual() {
        OffsetDateTime observedAt = OffsetDateTime.parse("2026-09-29T15:00:00Z");
        MarketSnapshot snapshot = completeSnapshot(observedAt);
        when(instrumentRepository.updateQuoteSnapshot(
                3L, snapshot.bidPrice(), snapshot.askPrice(), observedAt
        )).thenReturn(1);
        when(instrumentRepository.updateLastTradeSnapshot(
                3L, snapshot.lastPrice(), observedAt
        )).thenReturn(0);

        assertTrue(service.persist(3L, snapshot));
    }

    @Test
    void zeroRowsForOlderOrEqualObservationsAreNotReportedAsPersisted() {
        OffsetDateTime observedAt = OffsetDateTime.parse("2026-09-29T15:00:00Z");
        MarketSnapshot snapshot = completeSnapshot(observedAt);

        assertFalse(service.persist(3L, snapshot));

        verify(instrumentRepository).updateQuoteSnapshot(
                3L, snapshot.bidPrice(), snapshot.askPrice(), observedAt
        );
        verify(instrumentRepository).updateLastTradeSnapshot(
                3L, snapshot.lastPrice(), observedAt
        );
    }

    @Test
    void laterWriteFailurePropagates() {
        OffsetDateTime observedAt = OffsetDateTime.parse("2026-09-29T15:00:00Z");
        MarketSnapshot snapshot = completeSnapshot(observedAt);
        when(instrumentRepository.updateQuoteSnapshot(
                3L, snapshot.bidPrice(), snapshot.askPrice(), observedAt
        )).thenReturn(1);
        doThrow(new IllegalStateException("database failure"))
                .when(instrumentRepository)
                .updateLastTradeSnapshot(3L, snapshot.lastPrice(), observedAt);

        assertThrows(
                IllegalStateException.class,
                () -> service.persist(3L, snapshot)
        );
    }

    private MarketSnapshot completeSnapshot(OffsetDateTime observedAt) {
        return new MarketSnapshot(
                new BigDecimal("10.00"),
                new BigDecimal("10.10"),
                new BigDecimal("10.05"),
                observedAt,
                observedAt
        );
    }
}
