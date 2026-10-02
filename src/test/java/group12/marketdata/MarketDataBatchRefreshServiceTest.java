package group12.marketdata;

import group12.Entities.InstrumentEntity;
import group12.Repository.InstrumentRepository;
import group12.marketdata.exception.MarketDataProviderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@ExtendWith(OutputCaptureExtension.class)
class MarketDataBatchRefreshServiceTest {

    @Mock
    private InstrumentRepository instrumentRepository;

    @Mock
    private MarketDataProvider marketDataProvider;

    @Mock
    private MarketSnapshotPersistenceService persistenceService;

    private MarketDataBatchRefreshService service;

    @BeforeEach
    void setUp() {
        service = new MarketDataBatchRefreshService(
                instrumentRepository,
                marketDataProvider,
                persistenceService
        );
    }

    @Test
    void hasNoAlpacaPropertiesDependency() {
        assertTrue(Arrays.stream(MarketDataBatchRefreshService.class.getDeclaredFields())
                .noneMatch(field -> field.getType().equals(AlpacaProperties.class)));
        assertTrue(Arrays.stream(MarketDataBatchRefreshService.class.getConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes()))
                .noneMatch(type -> type.equals(AlpacaProperties.class)));
    }

    @Test
    void sendsOnlyProviderSupportedTradableRequestsInOneBatchCall() {
        InstrumentEntity aapl = instrument(1L, " aapl ", true, "Equity", "USD");
        InstrumentEntity nonTradable = instrument(2L, "MSFT", false, "Equity", "USD");
        InstrumentEntity fx = instrument(3L, "GBPUSD", true, "FX", "USD");
        InstrumentEntity nonUsd = instrument(4L, "TSLA", true, "Equity", "GBP");
        InstrumentEntity unsupported = instrument(5L, "NVDA", true, "Equity", "USD");
        when(instrumentRepository.findTradableInstruments()).thenReturn(
                List.of(aapl, nonTradable, fx, nonUsd, unsupported)
        );
        MarketDataRequest aaplRequest = new MarketDataRequest(" aapl ", "Equity", "USD");
        when(marketDataProvider.supports(aaplRequest)).thenReturn(true);
        MarketSnapshot snapshot = snapshot();
        when(marketDataProvider.getCurrentMarketSnapshots(any()))
                .thenReturn(Map.of("AAPL", snapshot));
        when(persistenceService.persist(1L, snapshot)).thenReturn(true);

        assertEquals(1, service.refreshEligibleInstruments());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<MarketDataRequest>> captor =
                ArgumentCaptor.forClass(Collection.class);
        verify(marketDataProvider).getCurrentMarketSnapshots(captor.capture());
        assertEquals(List.of(aaplRequest), List.copyOf(captor.getValue()));
        verify(marketDataProvider).supports(new MarketDataRequest("GBPUSD", "FX", "USD"));
        verify(marketDataProvider).supports(new MarketDataRequest("TSLA", "Equity", "GBP"));
        verify(marketDataProvider).supports(new MarketDataRequest("NVDA", "Equity", "USD"));
        verify(marketDataProvider, never()).supports(
                new MarketDataRequest("MSFT", "Equity", "USD")
        );
        verify(persistenceService).persist(1L, snapshot);
    }

    @Test
    void emptyEligibleSetSendsNoProviderRequest() {
        when(instrumentRepository.findTradableInstruments()).thenReturn(List.of());

        assertEquals(0, service.refreshEligibleInstruments());

        verify(marketDataProvider, never()).getCurrentMarketSnapshots(any());
        verify(persistenceService, never()).persist(anyLong(), any());
    }

    @Test
    void missingAndUnknownResponseSymbolsAreNotPersisted() {
        InstrumentEntity aapl = instrument(1L, "AAPL", true, "Equity", "USD");
        InstrumentEntity msft = instrument(2L, "MSFT", true, "Equity", "USD");
        when(instrumentRepository.findTradableInstruments()).thenReturn(List.of(aapl, msft));
        support(aapl, msft);
        MarketSnapshot snapshot = snapshot();
        when(marketDataProvider.getCurrentMarketSnapshots(any()))
                .thenReturn(Map.of("AAPL", snapshot, "UNKNOWN", snapshot));
        when(persistenceService.persist(1L, snapshot)).thenReturn(true);

        assertEquals(1, service.refreshEligibleInstruments());

        verify(persistenceService).persist(1L, snapshot);
        verify(persistenceService, times(1)).persist(anyLong(), any());
    }

    @Test
    void providerFailurePreservesAllCachedValues() {
        InstrumentEntity aapl = instrument(1L, "AAPL", true, "Equity", "USD");
        when(instrumentRepository.findTradableInstruments()).thenReturn(List.of(aapl));
        support(aapl);
        when(marketDataProvider.getCurrentMarketSnapshots(any()))
                .thenThrow(new MarketDataProviderException("provider failed"));

        assertThrows(
                MarketDataProviderException.class,
                () -> service.refreshEligibleInstruments()
        );

        verify(persistenceService, never()).persist(anyLong(), any());
    }

    @Test
    void unchangedSnapshotIsNotCountedOrWarned(CapturedOutput output) {
        InstrumentEntity aapl = instrument(1L, "AAPL", true, "Equity", "USD");
        when(instrumentRepository.findTradableInstruments()).thenReturn(List.of(aapl));
        support(aapl);
        MarketSnapshot snapshot = snapshot();
        when(marketDataProvider.getCurrentMarketSnapshots(any()))
                .thenReturn(Map.of("AAPL", snapshot));
        when(persistenceService.persist(1L, snapshot)).thenReturn(false);

        assertEquals(0, service.refreshEligibleInstruments());
        assertFalse(output.getOut().contains("Could not persist market snapshot"));
    }

    @Test
    void persistenceFailureForOneInstrumentDoesNotStopAnother(CapturedOutput output) {
        InstrumentEntity aapl = instrument(1L, "AAPL", true, "Equity", "USD");
        InstrumentEntity msft = instrument(2L, "MSFT", true, "Equity", "USD");
        when(instrumentRepository.findTradableInstruments()).thenReturn(List.of(aapl, msft));
        support(aapl, msft);
        MarketSnapshot snapshot = snapshot();
        when(marketDataProvider.getCurrentMarketSnapshots(any()))
                .thenReturn(Map.of("AAPL", snapshot, "MSFT", snapshot));
        doThrow(new IllegalStateException("database failure"))
                .when(persistenceService).persist(1L, snapshot);
        when(persistenceService.persist(2L, snapshot)).thenReturn(true);

        assertEquals(1, service.refreshEligibleInstruments());

        verify(persistenceService).persist(1L, snapshot);
        verify(persistenceService).persist(2L, snapshot);
        assertTrue(output.getOut().contains("instrument 1 (AAPL)"));
        assertTrue(output.getOut().contains("IllegalStateException: database failure"));
    }

    private void support(InstrumentEntity... instruments) {
        for (InstrumentEntity instrument : instruments) {
            when(marketDataProvider.supports(new MarketDataRequest(
                    instrument.getSymbol(),
                    instrument.getAssetClass(),
                    instrument.getCurrency()
            ))).thenReturn(true);
        }
    }

    private InstrumentEntity instrument(
            Long id,
            String symbol,
            boolean tradable,
            String assetClass,
            String currency
    ) {
        InstrumentEntity instrument = new InstrumentEntity();
        instrument.setInstrumentId(id);
        instrument.setSymbol(symbol);
        instrument.setInstrumentName(symbol);
        instrument.setTradable(tradable);
        instrument.setAssetClass(assetClass);
        instrument.setCurrency(currency);
        return instrument;
    }

    private MarketSnapshot snapshot() {
        return new MarketSnapshot(
                new BigDecimal("10.00"),
                new BigDecimal("10.10"),
                new BigDecimal("10.05"),
                OffsetDateTime.parse("2026-09-29T15:00:00Z"),
                OffsetDateTime.parse("2026-09-29T15:00:01Z")
        );
    }
}
