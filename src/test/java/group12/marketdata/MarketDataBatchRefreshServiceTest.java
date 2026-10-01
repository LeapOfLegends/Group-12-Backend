package group12.marketdata;

import group12.Entities.InstrumentEntity;
import group12.Repository.InstrumentRepository;
import group12.marketdata.exception.MarketDataConfigurationException;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

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

    private AlpacaProperties properties;
    private MarketDataBatchRefreshService service;

    @BeforeEach
    void setUp() {
        properties = new AlpacaProperties();
        properties.setSupportedUsEquitySymbols(
                Set.of("AAPL", "MSFT", "TSLA", "GBPUSD")
        );
        service = new MarketDataBatchRefreshService(
                instrumentRepository,
                marketDataProvider,
                persistenceService,
                properties
        );
    }

    @Test
    void sendsOnlyTradableAllowlistedUsdEquitiesInOneBatchCall() {
        InstrumentEntity aapl = instrument(1L, "AAPL", true, "Equity", "USD");
        InstrumentEntity nonTradable = instrument(2L, "MSFT", false, "Equity", "USD");
        InstrumentEntity fx = instrument(3L, "GBPUSD", true, "FX", "USD");
        InstrumentEntity nonUsd = instrument(4L, "TSLA", true, "Equity", "GBP");
        InstrumentEntity outsideAllowlist = instrument(5L, "NVDA", true, "Equity", "USD");
        when(instrumentRepository.findTradableUsdEquities()).thenReturn(
                List.of(aapl, nonTradable, fx, nonUsd, outsideAllowlist)
        );
        MarketSnapshot snapshot = snapshot();
        when(marketDataProvider.getCurrentMarketSnapshots(any()))
                .thenReturn(Map.of("AAPL", snapshot));
        when(persistenceService.persist(1L, snapshot)).thenReturn(true);

        assertEquals(1, service.refreshEligibleInstruments());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<InstrumentEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(marketDataProvider).getCurrentMarketSnapshots(captor.capture());
        assertEquals(List.of("AAPL"), captor.getValue().stream()
                .map(InstrumentEntity::getSymbol)
                .toList());
        verify(persistenceService).persist(1L, snapshot);
    }

    @Test
    void emptyEligibleSetSendsNoHttpRequest() {
        when(instrumentRepository.findTradableUsdEquities()).thenReturn(List.of());

        assertEquals(0, service.refreshEligibleInstruments());

        verify(marketDataProvider, never()).getCurrentMarketSnapshots(any());
        verify(persistenceService, never()).persist(anyLong(), any());
    }

    @Test
    void moreThanFiftyConfiguredSymbolsSkipsCycle() {
        Set<String> symbols = IntStream.rangeClosed(1, 51)
                .mapToObj(number -> "SYM" + number)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        properties.setSupportedUsEquitySymbols(symbols);

        assertThrows(
                MarketDataConfigurationException.class,
                () -> service.refreshEligibleInstruments()
        );

        verify(instrumentRepository, never()).findTradableUsdEquities();
        verify(marketDataProvider, never()).getCurrentMarketSnapshots(any());
    }

    @Test
    void missingAndUnknownResponseSymbolsAreNotPersisted() {
        InstrumentEntity aapl = instrument(1L, "AAPL", true, "Equity", "USD");
        InstrumentEntity msft = instrument(2L, "MSFT", true, "Equity", "USD");
        when(instrumentRepository.findTradableUsdEquities()).thenReturn(List.of(aapl, msft));
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
        when(instrumentRepository.findTradableUsdEquities()).thenReturn(
                List.of(instrument(1L, "AAPL", true, "Equity", "USD"))
        );
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
        when(instrumentRepository.findTradableUsdEquities()).thenReturn(List.of(aapl));
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
        when(instrumentRepository.findTradableUsdEquities()).thenReturn(List.of(aapl, msft));
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
