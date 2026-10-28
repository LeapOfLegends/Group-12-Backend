package group12.Services;

import group12.Entities.InstrumentEntity;
import group12.Entities.MarketCode;
import group12.Repository.InstrumentPriceHistoryRepository;
import group12.Repository.InstrumentRepository;
import group12.dto.InstrumentHistoryDTO;
import group12.marketdata.HistoricalBar;
import group12.marketdata.HistoricalMarketDataProvider;
import group12.marketdata.HistoricalTimeframe;
import group12.marketdata.exception.MarketDataProviderException;
import group12.marketdata.exception.PartialHistoricalDataException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InstrumentHistoryServiceTest {

    private static final Long INSTRUMENT_ID = 12L;
    private static final String SYMBOL = "AAPL";
    private static final Instant NOW = Instant.parse("2026-10-22T18:17:00Z");

    private InstrumentRepository instrumentRepository;
    private InstrumentPriceHistoryRepository historyRepository;
    private HistoricalMarketDataProvider provider;
    private List<StoredBar> store;
    private InstrumentHistoryService service;

    @BeforeEach
    void setUp() {
        instrumentRepository = mock(InstrumentRepository.class);
        historyRepository = mock(InstrumentPriceHistoryRepository.class);
        provider = mock(HistoricalMarketDataProvider.class);
        store = new ArrayList<>();

        when(instrumentRepository.findBySymbol(SYMBOL))
                .thenReturn(Optional.of(instrument()));
        when(instrumentRepository.findById(INSTRUMENT_ID))
                .thenReturn(Optional.of(instrument()));
        when(provider.supports(any())).thenReturn(true);
        when(historyRepository.findBars(eq(INSTRUMENT_ID), any(), any(), any()))
                .thenAnswer(invocation -> {
                    HistoricalTimeframe timeframe = invocation.getArgument(1);
                    OffsetDateTime start = invocation.getArgument(2);
                    OffsetDateTime end = invocation.getArgument(3);
                    return store.stream()
                            .filter(item -> item.timeframe() == timeframe)
                            .map(StoredBar::bar)
                            .filter(bar -> !bar.barStart().isBefore(start))
                            .filter(bar -> bar.barStart().isBefore(end))
                            .sorted(Comparator.comparing(HistoricalBar::barStart))
                            .toList();
                });
        when(historyRepository.upsertBars(eq(INSTRUMENT_ID), any(), any()))
                .thenAnswer(invocation -> {
                    HistoricalTimeframe timeframe = invocation.getArgument(1);
                    List<HistoricalBar> bars = invocation.getArgument(2);
                    bars.forEach(bar -> {
                        store.removeIf(item -> item.timeframe() == timeframe
                                && item.bar().barStart().equals(bar.barStart()));
                        store.add(new StoredBar(timeframe, bar));
                    });
                    return bars.size();
                });
        when(provider.getHistoricalBars(any(), any(), any(), any())).thenReturn(List.of());
        service = serviceAt(NOW);
    }

    @ParameterizedTest
    @CsvSource({
            "1D,FIVE_MINUTES", "5D,THIRTY_MINUTES", "1M,ONE_DAY",
            "1Y,ONE_WEEK", "5Y,ONE_MONTH"
    })
    void mapsEachRangeToItsFrozenTimeframe(
            String range,
            HistoricalTimeframe expectedTimeframe
    ) {
        service.getHistory(SYMBOL, range);

        verify(provider, atLeastOnce()).getHistoricalBars(
                any(), eq(expectedTimeframe), any(), any()
        );
    }

    @ParameterizedTest
    @CsvSource({
            "Crypto,CRYPTO,BTC/USD,1D,FIVE_MINUTES",
            "Crypto,CRYPTO,BTC/USD,5D,THIRTY_MINUTES",
            "Crypto,CRYPTO,BTC/USD,1M,ONE_DAY",
            "Crypto,CRYPTO,BTC/USD,1Y,ONE_WEEK",
            "Crypto,CRYPTO,BTC/USD,5Y,ONE_MONTH",
            "FOREX,FX,EUR/USD,1D,FIVE_MINUTES",
            "FOREX,FX,EUR/USD,5D,THIRTY_MINUTES",
            "FOREX,FX,EUR/USD,1M,ONE_DAY",
            "FOREX,FX,EUR/USD,1Y,ONE_WEEK",
            "FOREX,FX,EUR/USD,5Y,ONE_MONTH"
    })
    void mapsEveryCryptoAndFxRangeToTheFrozenTimeframe(
            String assetClass,
            MarketCode marketCode,
            String symbol,
            String range,
            HistoricalTimeframe expectedTimeframe
    ) {
        useInstrument(instrument(symbol, assetClass, marketCode));

        service.getHistory(INSTRUMENT_ID, range);

        verify(provider).getHistoricalBars(
                any(), eq(expectedTimeframe), any(), any()
        );
    }

    @Test
    void resolvesHistoryByInstrumentId() {
        InstrumentHistoryDTO result = service.getHistory(INSTRUMENT_ID, "1D");

        assertThat(result.instrumentId()).isEqualTo(INSTRUMENT_ID);
        assertThat(result.symbol()).isEqualTo(SYMBOL);
        verify(instrumentRepository).findById(INSTRUMENT_ID);
    }

    @Test
    void initialCacheMissPersistsProviderBarsAndRepeatedRequestUsesCache() {
        List<HistoricalBar> fetched = List.of(
                bar("2026-10-22T13:30:00Z", "250.25"),
                bar("2026-10-22T13:35:00Z", "250.80")
        );
        when(provider.getHistoricalBars(
                any(), eq(HistoricalTimeframe.FIVE_MINUTES), any(), any()
        )).thenReturn(fetched);

        InstrumentHistoryDTO first = service.getHistory(SYMBOL, "1D");
        InstrumentHistoryDTO second = service.getHistory(SYMBOL, "1D");

        assertThat(first.points()).hasSize(2);
        assertThat(second.points()).hasSize(2);
        assertThat(first.availableFrom()).isEqualTo(fetched.getFirst().barStart());
        assertThat(first.availableTo()).isEqualTo(fetched.getLast().barStart());
        verify(provider, times(1)).getHistoricalBars(
                any(), eq(HistoricalTimeframe.FIVE_MINUTES), any(), any()
        );
    }

    @Test
    void staleCacheFetchesOnlyTheMissingTail() {
        add(HistoricalTimeframe.ONE_DAY,
                bar("2026-09-23T04:00:00Z", "230"),
                bar("2026-10-20T04:00:00Z", "249"));
        when(provider.getHistoricalBars(
                any(), eq(HistoricalTimeframe.ONE_DAY), any(), any()
        )).thenReturn(List.of(bar("2026-10-21T04:00:00Z", "250")));

        InstrumentHistoryDTO result = service.getHistory(SYMBOL, "1M");

        ArgumentCaptor<OffsetDateTime> start = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(provider).getHistoricalBars(
                any(), eq(HistoricalTimeframe.ONE_DAY), start.capture(), any()
        );
        assertThat(start.getValue()).isEqualTo(OffsetDateTime.parse("2026-10-21T04:00:00Z"));
        assertThat(result.points()).extracting(point -> point.close())
                .contains(new BigDecimal("250"));
    }

    @Test
    void widerRequestBackfillsOnlyHistoryBeforeTheFirstCachedBar() {
        add(HistoricalTimeframe.ONE_WEEK,
                bar("2025-12-01T05:00:00Z", "200"),
                bar("2026-10-12T04:00:00Z", "249"));

        service.getHistory(SYMBOL, "1Y");

        ArgumentCaptor<OffsetDateTime> end = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(provider).getHistoricalBars(
                any(), eq(HistoricalTimeframe.ONE_WEEK), any(), end.capture()
        );
        assertThat(end.getValue()).isEqualTo(OffsetDateTime.parse("2025-12-01T05:00:00Z"));
    }

    @Test
    void weekendUsesLatestRepresentedRegularSession() {
        service = serviceAt(Instant.parse("2026-10-25T16:00:00Z"));
        add(HistoricalTimeframe.FIVE_MINUTES,
                bar("2026-10-22T13:30:00Z", "248"),
                bar("2026-10-23T13:30:00Z", "250"),
                bar("2026-10-23T13:35:00Z", "251"));

        InstrumentHistoryDTO result = service.getHistory(SYMBOL, "1D");

        assertThat(result.points()).hasSize(2);
        assertThat(result.points()).allMatch(point ->
                point.timestamp().toLocalDate().equals(OffsetDateTime.parse(
                        "2026-10-23T13:30:00Z"
                ).toLocalDate())
        );
    }

    @Test
    void holidayWithNoProviderBarsKeepsLatestRepresentedSessionWithoutFabrication() {
        service = serviceAt(Instant.parse("2026-12-25T17:00:00Z"));
        add(HistoricalTimeframe.FIVE_MINUTES,
                bar("2026-12-24T14:30:00Z", "250"),
                bar("2026-12-24T14:35:00Z", "251"));

        InstrumentHistoryDTO result = service.getHistory(SYMBOL, "1D");

        assertThat(result.partial()).isFalse();
        assertThat(result.points()).hasSize(2);
        assertThat(result.points()).allMatch(point ->
                point.timestamp().toLocalDate().equals(
                        OffsetDateTime.parse("2026-12-24T14:30:00Z").toLocalDate()
                )
        );
    }

    @Test
    void newYorkDstOffsetIsUsedForRegularSessionFiltering() {
        service = serviceAt(Instant.parse("2026-11-02T20:30:00Z"));
        add(HistoricalTimeframe.FIVE_MINUTES,
                bar("2026-11-02T13:30:00Z", "999"), // 08:30 EST, outside session
                bar("2026-11-02T14:30:00Z", "250"), // 09:30 EST
                bar("2026-11-02T14:35:00Z", "251"));

        InstrumentHistoryDTO result = service.getHistory(SYMBOL, "1D");

        assertThat(result.points()).extracting(point -> point.timestamp())
                .contains(OffsetDateTime.parse("2026-11-02T14:30:00Z"))
                .doesNotContain(OffsetDateTime.parse("2026-11-02T13:30:00Z"));
    }

    @Test
    void beforeFirstCompletedCandleKeepsPreviousRepresentedSession() {
        service = serviceAt(Instant.parse("2026-10-22T13:32:00Z"));
        add(HistoricalTimeframe.FIVE_MINUTES,
                bar("2026-10-21T19:55:00Z", "249"));

        InstrumentHistoryDTO result = service.getHistory(SYMBOL, "1D");

        assertThat(result.points()).singleElement()
                .extracting(point -> point.timestamp())
                .isEqualTo(OffsetDateTime.parse("2026-10-21T19:55:00Z"));
    }

    @Test
    void latestFiveDistinctRepresentedSessionsAreReturnedChronologically() {
        for (int day = 14; day <= 22; day++) {
            if (day == 17 || day == 18) {
                continue;
            }
            add(HistoricalTimeframe.THIRTY_MINUTES,
                    bar("2026-10-%02dT13:30:00Z".formatted(day), String.valueOf(day)));
        }

        InstrumentHistoryDTO result = service.getHistory(SYMBOL, "5D");

        assertThat(result.points()).hasSize(5);
        assertThat(result.points()).extracting(point -> point.close())
                .containsExactly(
                        new BigDecimal("16"), new BigDecimal("19"), new BigDecimal("20"),
                        new BigDecimal("21"), new BigDecimal("22")
                );
    }

    @Test
    void providerFailureFallsBackToUsableCacheAndMarksPartial() {
        add(HistoricalTimeframe.ONE_DAY,
                bar("2026-09-23T04:00:00Z", "230"),
                bar("2026-10-20T04:00:00Z", "249"));
        when(provider.getHistoricalBars(any(), any(), any(), any()))
                .thenThrow(new MarketDataProviderException("unavailable"));

        InstrumentHistoryDTO result = service.getHistory(SYMBOL, "1M");

        assertThat(result.partial()).isTrue();
        assertThat(result.points()).hasSize(2);
    }

    @Test
    void successfulEmptyProviderResponseIsAnEmptyNonPartialResult() {
        InstrumentHistoryDTO result = service.getHistory(SYMBOL, "1M");

        assertThat(result.points()).isEmpty();
        assertThat(result.availableFrom()).isNull();
        assertThat(result.availableTo()).isNull();
        assertThat(result.partial()).isFalse();
    }

    @Test
    void providerFailureWithoutUsableCacheIsServiceUnavailableAndCooldownAvoidsRetry() {
        when(provider.getHistoricalBars(any(), any(), any(), any()))
                .thenThrow(new MarketDataProviderException("unavailable"));

        assertServiceUnavailable(() -> service.getHistory(SYMBOL, "1M"));
        assertServiceUnavailable(() -> service.getHistory(SYMBOL, "1M"));
        verify(provider, times(1)).getHistoricalBars(any(), any(), any(), any());
    }

    @Test
    void unsupportedInstrumentIsUnprocessableAndDoesNotReadHistory() {
        when(provider.supports(any())).thenReturn(false);

        assertThatThrownBy(() -> service.getHistory(SYMBOL, "1D"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode().value()).isEqualTo(422)
                );
        verify(historyRepository, never()).findBars(any(), any(), any(), any());
    }

    @Test
    void cryptoOneDayUsesTrailingTwentyFourHoursWithoutEquitySessionFiltering() {
        useInstrument(instrument("BTC/USD", "Crypto", MarketCode.CRYPTO));
        when(provider.getHistoricalBars(
                any(), eq(HistoricalTimeframe.FIVE_MINUTES), any(), any()
        )).thenReturn(List.of(
                bar("2026-10-21T19:00:00Z", "64000"),
                bar("2026-10-22T03:00:00Z", "64500")
        ));

        InstrumentHistoryDTO result = service.getHistory(INSTRUMENT_ID, "1D");

        ArgumentCaptor<OffsetDateTime> start = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> end = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(provider).getHistoricalBars(
                any(), eq(HistoricalTimeframe.FIVE_MINUTES), start.capture(), end.capture()
        );
        assertThat(start.getValue()).isEqualTo(time("2026-10-21T18:17:00Z"));
        assertThat(end.getValue()).isEqualTo(time("2026-10-22T18:15:00Z"));
        assertThat(result.points()).hasSize(2);
    }

    @Test
    void fxFiveDaysUsesTrailingOneHundredTwentyHoursAndPreservesWeekendGaps() {
        useInstrument(instrument("EUR/USD", "FOREX", MarketCode.FX));
        when(provider.getHistoricalBars(
                any(), eq(HistoricalTimeframe.THIRTY_MINUTES), any(), any()
        )).thenReturn(List.of(
                bar("2026-10-19T00:00:00Z", "1.10"),
                bar("2026-10-21T00:00:00Z", "1.12")
        ));

        InstrumentHistoryDTO result = service.getHistory(INSTRUMENT_ID, "5D");

        ArgumentCaptor<OffsetDateTime> start = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> end = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(provider).getHistoricalBars(
                any(), eq(HistoricalTimeframe.THIRTY_MINUTES), start.capture(), end.capture()
        );
        assertThat(start.getValue()).isEqualTo(time("2026-10-17T18:17:00Z"));
        assertThat(end.getValue()).isEqualTo(time("2026-10-22T18:00:00Z"));
        assertThat(result.points()).extracting(point -> point.timestamp())
                .containsExactly(time("2026-10-19T00:00:00Z"), time("2026-10-21T00:00:00Z"));
    }

    @Test
    void successfulEmptyFxWeekendCoverageDoesNotRefetchEveryNewBoundary() {
        useInstrument(instrument("EUR/USD", "FOREX", MarketCode.FX));
        Clock advancingClock = mock(Clock.class);
        when(advancingClock.instant()).thenReturn(
                Instant.parse("2026-10-24T10:01:00Z"),
                Instant.parse("2026-10-24T10:06:00Z")
        );
        service = new InstrumentHistoryService(
                instrumentRepository,
                historyRepository,
                List.of(provider),
                advancingClock
        );

        service.getHistory(INSTRUMENT_ID, "1D");
        service.getHistory(INSTRUMENT_ID, "1D");

        verify(provider, times(1)).getHistoricalBars(
                any(), eq(HistoricalTimeframe.FIVE_MINUTES), any(), any()
        );
    }

    @ParameterizedTest
    @CsvSource({
            "1M,ONE_DAY,2026-10-22T00:00:00Z",
            "1Y,ONE_WEEK,2026-10-19T00:00:00Z",
            "5Y,ONE_MONTH,2026-10-01T00:00:00Z"
    })
    void cryptoAndFxUseUtcCalendarCompletionBoundaries(
            String range,
            HistoricalTimeframe timeframe,
            String expectedEnd
    ) {
        useInstrument(instrument("BTC/USD", "Crypto", MarketCode.CRYPTO));

        service.getHistory(INSTRUMENT_ID, range);

        ArgumentCaptor<OffsetDateTime> end = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(provider).getHistoricalBars(any(), eq(timeframe), any(), end.capture());
        assertThat(end.getValue()).isEqualTo(time(expectedEnd));
    }

    @Test
    void selectsExactlyOneSupportingProviderAndRejectsAmbiguousRouting() {
        HistoricalMarketDataProvider unsupported = mock(HistoricalMarketDataProvider.class);
        when(unsupported.supports(any())).thenReturn(false);
        service = new InstrumentHistoryService(
                instrumentRepository,
                historyRepository,
                List.of(unsupported, provider),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        service.getHistory(INSTRUMENT_ID, "1M");
        verify(provider).getHistoricalBars(any(), any(), any(), any());
        verify(unsupported, never()).getHistoricalBars(any(), any(), any(), any());

        when(unsupported.supports(any())).thenReturn(true);
        assertThatThrownBy(() -> service.getHistory(INSTRUMENT_ID, "1M"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                );
    }

    @Test
    void incompleteBarsAreNotPersistedAndKnownPartialBarsAreUsable() {
        useInstrument(instrument("BTC/USD", "Crypto", MarketCode.CRYPTO));
        HistoricalBar completed = bar("2026-10-22T12:00:00Z", "64000");
        HistoricalBar incomplete = new HistoricalBar(
                time("2026-10-22T12:05:00Z"), new BigDecimal("64100"), false
        );
        when(provider.getHistoricalBars(any(), any(), any(), any()))
                .thenThrow(new PartialHistoricalDataException(
                        "limited", null, List.of(completed, incomplete)
                ));

        InstrumentHistoryDTO result = service.getHistory(INSTRUMENT_ID, "1D");

        assertThat(result.partial()).isTrue();
        assertThat(result.points()).singleElement()
                .extracting(point -> point.close()).isEqualTo(new BigDecimal("64000"));
        assertThat(store).singleElement().satisfies(item ->
                assertThat(item.bar().complete()).isTrue()
        );
    }

    @Test
    void missingInstrumentIsNotFoundAndInvalidRangeIsBadRequestInput() {
        when(instrumentRepository.findBySymbol("MISSING")).thenReturn(Optional.empty());
        when(instrumentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getHistory("MISSING", "1D"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND)
                );
        assertThatThrownBy(() -> service.getHistory(99L, "1D"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND)
                );
        assertThatThrownBy(() -> service.getHistory(SYMBOL, "ALL"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void serviceHasNoLongRunningTransactionalBoundary() {
        assertThat(InstrumentHistoryService.class.getAnnotation(Transactional.class)).isNull();
    }

    private InstrumentHistoryService serviceAt(Instant instant) {
        return new InstrumentHistoryService(
                instrumentRepository,
                historyRepository,
                List.of(provider),
                Clock.fixed(instant, ZoneOffset.UTC)
        );
    }

    private void assertServiceUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                );
    }

    private void add(HistoricalTimeframe timeframe, HistoricalBar... bars) {
        for (HistoricalBar bar : bars) {
            store.add(new StoredBar(timeframe, bar));
        }
    }

    private HistoricalBar bar(String timestamp, String close) {
        return new HistoricalBar(
                OffsetDateTime.parse(timestamp), new BigDecimal(close), true
        );
    }

    private InstrumentEntity instrument() {
        return instrument("AAPL", "Equity", MarketCode.IEX);
    }

    private InstrumentEntity instrument(
            String symbol,
            String assetClass,
            MarketCode marketCode
    ) {
        InstrumentEntity instrument = new InstrumentEntity();
        instrument.setInstrumentId(INSTRUMENT_ID);
        instrument.setMarketCode(marketCode);
        instrument.setSymbol(symbol);
        instrument.setInstrumentName(symbol);
        instrument.setAssetClass(assetClass);
        instrument.setCurrency("USD");
        instrument.setTradable(true);
        return instrument;
    }

    private void useInstrument(InstrumentEntity instrument) {
        when(instrumentRepository.findById(INSTRUMENT_ID)).thenReturn(Optional.of(instrument));
        when(instrumentRepository.findBySymbol(any())).thenReturn(Optional.of(instrument));
    }

    private OffsetDateTime time(String value) {
        return OffsetDateTime.parse(value);
    }

    private record StoredBar(HistoricalTimeframe timeframe, HistoricalBar bar) {
    }
}
