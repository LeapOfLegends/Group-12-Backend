package group12.Services;

import group12.Entities.ClientEntity;
import group12.Entities.InstrumentEntity;
import group12.Entities.MarketCode;
import group12.Repository.ClientRepository;
import group12.dto.PortfolioFinancialEvent;
import group12.dto.PortfolioFinancialHistory;
import group12.dto.PortfolioFinancialState;
import group12.dto.PortfolioPerformanceDTO;
import group12.exception.FinancialHistoryReconstructionException;
import group12.marketdata.HistoricalBar;
import group12.marketdata.HistoricalTimeframe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortfolioPerformanceServiceTest {

    private static final long CLIENT_ID = 7L;
    private static final long INSTRUMENT_ID = 42L;
    private static final Instant NOW = Instant.parse("2026-10-27T12:02:00Z");

    @Mock ClientRepository clientRepository;
    @Mock PortfolioFinancialHistoryService financialHistoryService;
    @Mock InstrumentHistoryService instrumentHistoryService;

    private PortfolioPerformanceService service;
    private List<PortfolioFinancialEvent> events;

    @BeforeEach
    void setUp() {
        ClientEntity client = new ClientEntity();
        client.setClientId(CLIENT_ID);
        client.setCreatedAt(LocalDateTime.parse("2026-10-01T00:00:00"));
        when(clientRepository.findById(CLIENT_ID)).thenReturn(client);
        events = new ArrayList<>();
        when(financialHistoryService.reconstruct(eq(CLIENT_ID), anyList()))
                .thenAnswer(invocation -> reconstruct(invocation.getArgument(1)));
        service = new PortfolioPerformanceService(
                clientRepository, financialHistoryService, instrumentHistoryService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void newUnfundedAccountHasZeroValueAndUndefinedReturn() {
        PortfolioPerformanceDTO result = service.getHistory(CLIENT_ID, "1D");

        assertThat(result.partial()).isFalse();
        assertThat(result.points()).isNotEmpty();
        assertThat(result.points()).allSatisfy(point -> {
            assertThat(point.totalValue()).isEqualByComparingTo("0.00");
            assertThat(point.cumulativeReturnPercent()).isNull();
        });
        verify(instrumentHistoryService, never()).loadCompletedPriceHistory(
                any(), any(), any(), any()
        );
    }

    @Test
    void cashDepositChangesValueWithoutCreatingReturn() {
        addFunding(1L, "DEPOSIT", "1000", "2026-10-26T18:00:00Z");

        PortfolioPerformanceDTO result = service.getHistory(CLIENT_ID, "1D");

        assertThat(result.points().getLast().totalValue()).isEqualByComparingTo("1000.00");
        assertThat(result.points().getLast().cumulativeReturnPercent())
                .isEqualByComparingTo("0.000000");
    }

    @Test
    void cashWithdrawalDoesNotCreateNegativeReturn() {
        addFunding(1L, "DEPOSIT", "1000", "2026-10-25T12:00:00Z");
        addFunding(2L, "WITHDRAWAL", "-400", "2026-10-26T18:00:00Z");

        PortfolioPerformanceDTO result = service.getHistory(CLIENT_ID, "1D");

        assertThat(result.points().getLast().totalValue()).isEqualByComparingTo("600.00");
        assertThat(result.points().getLast().cumulativeReturnPercent())
                .isEqualByComparingTo("0.000000");
    }

    @Test
    void buyAtUnchangedPriceMovesCashIntoHoldingsWithoutArtificialReturn() {
        addFunding(1L, "DEPOSIT", "1000", "2026-10-25T12:00:00Z");
        events.add(new PortfolioFinancialEvent(
                2L, time("2026-10-26T18:00:00Z"), "BUY", new BigDecimal("-500"),
                new BigDecimal("500"), 8L, INSTRUMENT_ID, new BigDecimal("5")
        ));
        stubEquityPrices("100");

        PortfolioPerformanceDTO result = service.getHistory(CLIENT_ID, "1D");

        assertThat(result.points().getLast().cashValue()).isEqualByComparingTo("500.00");
        assertThat(result.points().getLast().holdingsValue()).isEqualByComparingTo("500.00");
        assertThat(result.points().getLast().cumulativeReturnPercent())
                .isEqualByComparingTo("0.000000");
    }

    @Test
    void marketAppreciationIncreasesValueAndTimeWeightedReturn() {
        addFunding(1L, "DEPOSIT", "1000", "2026-10-25T12:00:00Z");
        events.add(new PortfolioFinancialEvent(
                2L, time("2026-10-26T11:00:00Z"), "BUY", new BigDecimal("-1000"),
                BigDecimal.ZERO, 8L, INSTRUMENT_ID, new BigDecimal("10")
        ));
        InstrumentEntity instrument = equity("AAPL", "USD");
        when(instrumentHistoryService.loadCompletedPriceHistory(
                eq(INSTRUMENT_ID), eq(HistoricalTimeframe.FIVE_MINUTES), any(), any()
        )).thenReturn(new InstrumentHistoryService.PriceHistory(
                instrument, HistoricalTimeframe.FIVE_MINUTES,
                List.of(
                        bar("2026-10-26T11:55:00Z", "100"),
                        bar("2026-10-27T11:55:00Z", "110")
                ), false
        ));

        PortfolioPerformanceDTO result = service.getHistory(CLIENT_ID, "1D");

        assertThat(result.points().getLast().totalValue()).isEqualByComparingTo("1100.00");
        assertThat(result.points().getLast().cumulativeReturnPercent())
                .isEqualByComparingTo("10.000000");
    }

    @Test
    void missingAllRequiredHistoricalPricesReturnsServiceUnavailable() {
        addFunding(1L, "DEPOSIT", "1000", "2026-10-25T12:00:00Z");
        events.add(new PortfolioFinancialEvent(
                2L, time("2026-10-26T11:00:00Z"), "BUY", new BigDecimal("-1000"),
                BigDecimal.ZERO, 8L, INSTRUMENT_ID, new BigDecimal("10")
        ));
        when(instrumentHistoryService.loadCompletedPriceHistory(any(), any(), any(), any()))
                .thenReturn(new InstrumentHistoryService.PriceHistory(
                        equity("AAPL", "USD"), HistoricalTimeframe.FIVE_MINUTES,
                        List.of(), true
                ));

        assertThatThrownBy(() -> service.getHistory(CLIENT_ID, "1D"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode().value()).isEqualTo(503)
                );
    }

    @Test
    void candleCloseIsNotUsedBeforeCompletion() {
        addFunding(1L, "DEPOSIT", "100", "2026-10-25T12:00:00Z");
        events.add(new PortfolioFinancialEvent(
                2L, time("2026-10-26T11:00:00Z"), "BUY", new BigDecimal("-100"),
                BigDecimal.ZERO, 8L, INSTRUMENT_ID, BigDecimal.ONE
        ));
        when(instrumentHistoryService.loadCompletedPriceHistory(any(), any(), any(), any()))
                .thenReturn(new InstrumentHistoryService.PriceHistory(
                        equity("AAPL", "USD"), HistoricalTimeframe.FIVE_MINUTES,
                        List.of(bar("2026-10-27T11:58:00Z", "999")), false
                ));

        assertThatThrownBy(() -> service.getHistory(CLIENT_ID, "1D"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode().value()).isEqualTo(503)
                );
    }

    @Test
    void nonUsdQuotedFxFailsExplicitlyInsteadOfBeingOmitted() {
        addFunding(1L, "DEPOSIT", "100", "2026-10-25T12:00:00Z");
        events.add(new PortfolioFinancialEvent(
                2L, time("2026-10-26T11:00:00Z"), "BUY", new BigDecimal("-100"),
                BigDecimal.ZERO, 8L, INSTRUMENT_ID, BigDecimal.ONE
        ));
        InstrumentEntity instrument = equity("USD/JPY", "JPY");
        instrument.setMarketCode(MarketCode.FX);
        instrument.setAssetClass("FOREX");
        when(instrumentHistoryService.loadCompletedPriceHistory(any(), any(), any(), any()))
                .thenReturn(new InstrumentHistoryService.PriceHistory(
                        instrument, HistoricalTimeframe.FIVE_MINUTES,
                        List.of(bar("2026-10-26T11:00:00Z", "150")), false
                ));

        assertThatThrownBy(() -> service.getHistory(CLIENT_ID, "1D"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode().value()).isEqualTo(422)
                );
    }

    @Test
    void sellAndFullLiquidationMoveHoldingsBackToCashWithoutArtificialReturn() {
        addFunding(1L, "DEPOSIT", "1000", "2026-10-25T12:00:00Z");
        events.add(new PortfolioFinancialEvent(
                2L, time("2026-10-25T13:00:00Z"), "BUY", new BigDecimal("-1000"),
                BigDecimal.ZERO, 8L, INSTRUMENT_ID, new BigDecimal("10")
        ));
        events.add(new PortfolioFinancialEvent(
                3L, time("2026-10-26T18:00:00Z"), "SELL", new BigDecimal("1000"),
                new BigDecimal("1000"), 9L, INSTRUMENT_ID, new BigDecimal("-10")
        ));
        stubEquityPrices("100");

        PortfolioPerformanceDTO result = service.getHistory(CLIENT_ID, "1D");

        assertThat(result.points().getLast().cashValue()).isEqualByComparingTo("1000.00");
        assertThat(result.points().getLast().holdingsValue()).isEqualByComparingTo("0.00");
        assertThat(result.points().getLast().cumulativeReturnPercent())
                .isEqualByComparingTo("0.000000");
    }

    @Test
    void fullWithdrawalLeavesPriorPerformanceDefinedWithoutDivisionByZero() {
        addFunding(1L, "DEPOSIT", "1000", "2026-10-25T12:00:00Z");
        addFunding(2L, "WITHDRAWAL", "-1000", "2026-10-26T18:00:00Z");

        PortfolioPerformanceDTO result = service.getHistory(CLIENT_ID, "1D");

        assertThat(result.points().getLast().totalValue()).isEqualByComparingTo("0.00");
        assertThat(result.points().getLast().cumulativeReturnPercent())
                .isEqualByComparingTo("0.000000");
    }

    @Test
    void geometricallyLinksReturnsAcrossAnIntraperiodDeposit() {
        addFunding(1L, "DEPOSIT", "1000", "2026-10-25T12:00:00Z");
        events.add(new PortfolioFinancialEvent(
                2L, time("2026-10-25T13:00:00Z"), "BUY", new BigDecimal("-1000"),
                BigDecimal.ZERO, 8L, INSTRUMENT_ID, new BigDecimal("10")
        ));
        addFunding(3L, "DEPOSIT", "1100", "2026-10-26T18:00:00Z");
        when(instrumentHistoryService.loadCompletedPriceHistory(any(), any(), any(), any()))
                .thenReturn(new InstrumentHistoryService.PriceHistory(
                        equity("AAPL", "USD"), HistoricalTimeframe.FIVE_MINUTES,
                        List.of(
                                bar("2026-10-26T11:55:00Z", "100"),
                                bar("2026-10-26T17:50:00Z", "110"),
                                bar("2026-10-27T11:55:00Z", "121")
                        ), false
                ));

        PortfolioPerformanceDTO result = service.getHistory(CLIENT_ID, "1D");

        assertThat(result.points().getLast().totalValue()).isEqualByComparingTo("2310.00");
        assertThat(result.points().getLast().cumulativeReturnPercent())
                .isEqualByComparingTo("15.500000");
    }

    @ParameterizedTest
    @CsvSource({
            "1D,5Min", "5D,30Min", "1M,1Day", "1Y,1Week", "5Y,1Month"
    })
    void supportsEveryFrozenChartRange(String range, String timeframe) {
        PortfolioPerformanceDTO result = service.getHistory(CLIENT_ID, range);

        assertThat(result.range()).isEqualTo(range);
        assertThat(result.timeframe()).isEqualTo(timeframe);
        assertThat(result.points()).isNotEmpty();
        assertThat(result.points()).isSortedAccordingTo(
                (left, right) -> left.timestamp().compareTo(right.timestamp())
        );
    }

    @ParameterizedTest
    @CsvSource({"BTC/USD,CRYPTO,64000", "EUR/USD,FX,1.12"})
    void valuesUsdQuotedCryptoAndFxUsingExistingQuantityConvention(
            String symbol,
            MarketCode marketCode,
            String price
    ) {
        addFunding(1L, "DEPOSIT", "100000", "2026-10-25T12:00:00Z");
        events.add(new PortfolioFinancialEvent(
                2L, time("2026-10-27T11:50:00Z"), "BUY", new BigDecimal("-10"),
                new BigDecimal("99990"), 8L, INSTRUMENT_ID, new BigDecimal("2")
        ));
        InstrumentEntity instrument = equity(symbol, "USD");
        instrument.setMarketCode(marketCode);
        instrument.setAssetClass(marketCode == MarketCode.FX ? "FOREX" : "Crypto");
        when(instrumentHistoryService.loadCompletedPriceHistory(any(), any(), any(), any()))
                .thenReturn(new InstrumentHistoryService.PriceHistory(
                        instrument, HistoricalTimeframe.FIVE_MINUTES,
                        List.of(bar("2026-10-27T11:55:00Z", price)), false
                ));

        PortfolioPerformanceDTO result = service.getHistory(CLIENT_ID, "1D");

        assertThat(result.points().getLast().holdingsValue())
                .isEqualByComparingTo(new BigDecimal(price).multiply(new BigDecimal("2")));
    }

    @Test
    void accountCreationTruncatesReturnedTimeline() {
        ClientEntity recent = new ClientEntity();
        recent.setClientId(CLIENT_ID);
        recent.setCreatedAt(LocalDateTime.parse("2026-10-27T11:47:00"));
        when(clientRepository.findById(CLIENT_ID)).thenReturn(recent);

        PortfolioPerformanceDTO result = service.getHistory(CLIENT_ID, "1D");

        assertThat(result.availableFrom()).isEqualTo(time("2026-10-27T11:50:00Z"));
        assertThat(result.points()).allMatch(point ->
                !point.timestamp().isBefore(time("2026-10-27T11:47:00Z"))
        );
    }

    @Test
    void arbitraryTimestampValuationUsesHistoricalStateAndCompletedPrice() {
        addFunding(1L, "DEPOSIT", "1000", "2026-10-25T12:00:00Z");
        events.add(new PortfolioFinancialEvent(
                2L, time("2026-10-26T11:00:00Z"), "BUY", new BigDecimal("-500"),
                new BigDecimal("500"), 8L, INSTRUMENT_ID, new BigDecimal("5")
        ));
        stubEquityPrices("110");

        var point = service.getValuation(
                CLIENT_ID, time("2026-10-27T12:00:00Z"), HistoricalTimeframe.FIVE_MINUTES
        );

        assertThat(point.cashValue()).isEqualByComparingTo("500.00");
        assertThat(point.holdingsValue()).isEqualByComparingTo("550.00");
        assertThat(point.totalValue()).isEqualByComparingTo("1050.00");
    }

    @Test
    void financialReconstructionFailuresPropagateWithoutPartialResponse() {
        reset(financialHistoryService);
        when(financialHistoryService.reconstruct(eq(CLIENT_ID), anyList()))
                .thenThrow(new FinancialHistoryReconstructionException(CLIENT_ID, "broken ledger"));

        assertThatThrownBy(() -> service.getHistory(CLIENT_ID, "1D"))
                .isInstanceOf(FinancialHistoryReconstructionException.class)
                .hasMessageContaining("broken ledger");
    }

    private PortfolioFinancialHistory reconstruct(List<OffsetDateTime> timestamps) {
        List<PortfolioFinancialState> states = timestamps.stream().map(timestamp -> {
            BigDecimal cash = BigDecimal.ZERO;
            Map<Long, BigDecimal> quantities = new HashMap<>();
            for (PortfolioFinancialEvent event : events) {
                if (event.timestamp().isAfter(timestamp)) {
                    continue;
                }
                cash = cash.add(event.amount());
                if (event.instrumentId() != null) {
                    quantities.merge(event.instrumentId(), event.quantityDelta(), BigDecimal::add);
                    quantities.entrySet().removeIf(entry -> entry.getValue().signum() == 0);
                }
            }
            return new PortfolioFinancialState(timestamp, cash, quantities);
        }).toList();
        return new PortfolioFinancialHistory(CLIENT_ID, events, states);
    }

    private void addFunding(long id, String type, String amount, String timestamp) {
        events.add(new PortfolioFinancialEvent(
                id, time(timestamp), type, new BigDecimal(amount), BigDecimal.ZERO,
                null, null, null
        ));
    }

    private void stubEquityPrices(String price) {
        when(instrumentHistoryService.loadCompletedPriceHistory(
                eq(INSTRUMENT_ID), eq(HistoricalTimeframe.FIVE_MINUTES), any(), any()
        )).thenReturn(new InstrumentHistoryService.PriceHistory(
                equity("AAPL", "USD"), HistoricalTimeframe.FIVE_MINUTES,
                List.of(bar("2026-10-26T11:55:00Z", price)), false
        ));
    }

    private InstrumentEntity equity(String symbol, String currency) {
        InstrumentEntity instrument = new InstrumentEntity();
        instrument.setInstrumentId(INSTRUMENT_ID);
        instrument.setSymbol(symbol);
        instrument.setCurrency(currency);
        instrument.setAssetClass("EQUITY");
        instrument.setMarketCode(MarketCode.IEX);
        return instrument;
    }

    private HistoricalBar bar(String timestamp, String price) {
        return new HistoricalBar(time(timestamp), new BigDecimal(price), true);
    }

    private OffsetDateTime time(String timestamp) {
        return OffsetDateTime.parse(timestamp);
    }
}
