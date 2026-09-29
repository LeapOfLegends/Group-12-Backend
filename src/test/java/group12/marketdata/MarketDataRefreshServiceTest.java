package group12.marketdata;

import group12.Entities.InstrumentEntity;
import group12.Repository.InstrumentRepository;
import group12.marketdata.exception.MarketDataProviderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketDataRefreshServiceTest {

    @Mock
    private InstrumentRepository instrumentRepository;

    @Mock
    private MarketDataProvider marketDataProvider;

    @Mock
    private MarketSnapshotPersistenceService persistenceService;

    private MarketDataRefreshService refreshService;
    private InstrumentEntity instrument;

    @BeforeEach
    void setUp() {
        refreshService = new MarketDataRefreshService(
                instrumentRepository,
                marketDataProvider,
                persistenceService
        );
        instrument = new InstrumentEntity();
        instrument.setInstrumentId(7L);
        instrument.setSymbol("AAPL");
        instrument.setAssetClass("Equity");
        instrument.setCurrency("USD");
    }

    @Test
    void requestsSnapshotBeforeDelegatingToTransactionalPersistence() {
        MarketSnapshot snapshot = completeSnapshot();
        when(instrumentRepository.findById(7L)).thenReturn(Optional.of(instrument));
        when(marketDataProvider.getCurrentMarketSnapshot(instrument)).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return snapshot;
        });

        MarketSnapshot result = refreshService.refreshInstrument(7L);

        assertEquals(snapshot, result);
        var inOrder = org.mockito.Mockito.inOrder(marketDataProvider, persistenceService);
        inOrder.verify(marketDataProvider).getCurrentMarketSnapshot(instrument);
        inOrder.verify(persistenceService).persist(7L, snapshot);
    }

    @Test
    void providerFailureDoesNotTouchCachedValues() {
        when(instrumentRepository.findById(7L)).thenReturn(Optional.of(instrument));
        when(marketDataProvider.getCurrentMarketSnapshot(instrument))
                .thenThrow(new MarketDataProviderException("provider failed"));

        assertThrows(
                MarketDataProviderException.class,
                () -> refreshService.refreshInstrument(7L)
        );

        verify(persistenceService, never()).persist(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    private MarketSnapshot completeSnapshot() {
        return new MarketSnapshot(
                new BigDecimal("10.00"),
                new BigDecimal("10.10"),
                new BigDecimal("10.05"),
                OffsetDateTime.parse("2026-09-29T15:00:00Z"),
                OffsetDateTime.parse("2026-09-29T15:00:01Z")
        );
    }
}
