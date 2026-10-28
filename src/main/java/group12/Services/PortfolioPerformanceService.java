package group12.Services;

import group12.Entities.ClientEntity;
import group12.Entities.InstrumentEntity;
import group12.Entities.MarketCode;
import group12.Repository.ClientRepository;
import group12.dto.PortfolioFinancialEvent;
import group12.dto.PortfolioFinancialHistory;
import group12.dto.PortfolioFinancialState;
import group12.dto.PortfolioPerformanceDTO;
import group12.dto.PortfolioPerformancePointDTO;
import group12.exception.ClientNotFoundException;
import group12.marketdata.HistoricalBar;
import group12.marketdata.HistoricalRange;
import group12.marketdata.HistoricalTimeframe;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

@Service
public class PortfolioPerformanceService {

    private static final MathContext CALCULATION_CONTEXT = new MathContext(24, RoundingMode.HALF_EVEN);
    private static final int MONEY_SCALE = 2;
    private static final int RETURN_SCALE = 6;
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final LocalTime EQUITY_CLOSE = LocalTime.of(16, 0);
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private final ClientRepository clientRepository;
    private final PortfolioFinancialHistoryService financialHistoryService;
    private final InstrumentHistoryService instrumentHistoryService;
    private final Clock clock;

    public PortfolioPerformanceService(
            ClientRepository clientRepository,
            PortfolioFinancialHistoryService financialHistoryService,
            InstrumentHistoryService instrumentHistoryService,
            @Qualifier("instrumentHistoryClock") Clock clock
    ) {
        this.clientRepository = clientRepository;
        this.financialHistoryService = financialHistoryService;
        this.instrumentHistoryService = instrumentHistoryService;
        this.clock = clock;
    }

    public PortfolioPerformanceDTO getHistory(Long clientId, String requestedRange) {
        Objects.requireNonNull(clientId, "clientId is required");
        HistoricalRange range = HistoricalRange.fromApiValue(requestedRange);
        OffsetDateTime referenceTime = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        ClientEntity client = clientRepository.findById(clientId);
        if (client == null) {
            throw new ClientNotFoundException("Client not found with id: " + clientId);
        }
        if (client.getCreatedAt() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Account creation time is missing");
        }
        OffsetDateTime accountCreatedAt = PortfolioFinancialHistoryService.accountCreatedAt(
                client.getCreatedAt()
        );
        if (accountCreatedAt.isAfter(referenceTime)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Account creation time is in the future"
            );
        }
        OffsetDateTime rangeStart = later(rangeStart(referenceTime, range), accountCreatedAt);
        OffsetDateTime rangeEnd = completedPortfolioBoundary(referenceTime, range.getTimeframe());

        List<OffsetDateTime> displayTimes = displayTimes(
                rangeStart, rangeEnd, range.getTimeframe()
        );
        if (displayTimes.isEmpty() && !accountCreatedAt.isAfter(referenceTime)) {
            // A just-created cash-only account still has a truthful zero observation even
            // before the first daily/weekly/monthly market boundary.
            displayTimes = List.of(accountCreatedAt);
            rangeEnd = accountCreatedAt;
        }
        final OffsetDateTime historyEnd = rangeEnd;

        PortfolioFinancialHistory initial = financialHistoryService.reconstruct(
                clientId, displayTimes
        );
        OffsetDateTime firstDisplayTime = displayTimes.isEmpty()
                ? rangeStart : displayTimes.getFirst();
        List<PortfolioFinancialEvent> externalFlows = initial.events().stream()
                .filter(this::isExternalFlow)
                // The first displayed valuation is the selected range's origin. Flows
                // before it are already reflected in that value and must not be linked.
                .filter(event -> event.timestamp().isAfter(firstDisplayTime))
                .filter(event -> !event.timestamp().isAfter(historyEnd))
                .toList();

        TreeSet<OffsetDateTime> valuationTimes = new TreeSet<>(displayTimes);
        for (PortfolioFinancialEvent flow : externalFlows) {
            valuationTimes.add(flow.timestamp().minusNanos(1));
            valuationTimes.add(flow.timestamp());
        }
        List<OffsetDateTime> allTimes = List.copyOf(valuationTimes);
        PortfolioFinancialHistory history = allTimes.equals(displayTimes)
                ? initial
                : financialHistoryService.reconstruct(clientId, allTimes);

        Set<Long> instrumentIds = new LinkedHashSet<>();
        history.states().forEach(state -> instrumentIds.addAll(state.instrumentQuantities().keySet()));
        Map<Long, InstrumentHistoryService.PriceHistory> prices = loadPrices(
                instrumentIds, range.getTimeframe(), allTimes
        );

        boolean providerPartial = prices.values().stream().anyMatch(
                InstrumentHistoryService.PriceHistory::partial
        );
        Map<OffsetDateTime, Valuation> valuations = new LinkedHashMap<>();
        for (PortfolioFinancialState state : history.states()) {
            valuations.put(state.timestamp(), value(state, prices));
        }

        TwrResult twr = calculateTwr(displayTimes, externalFlows, valuations);
        List<PortfolioPerformancePointDTO> points = new ArrayList<>();
        int unavailable = 0;
        boolean estimated = false;
        for (OffsetDateTime timestamp : displayTimes) {
            Valuation valuation = valuations.get(timestamp);
            if (valuation == null || !valuation.available()) {
                unavailable++;
                continue;
            }
            estimated |= valuation.estimated();
            points.add(new PortfolioPerformancePointDTO(
                    timestamp,
                    money(valuation.cash()),
                    money(valuation.holdings()),
                    money(valuation.total()),
                    twr.returns().get(timestamp),
                    valuation.estimated() ? "CARRIED_FORWARD" : "COMPLETE"
            ));
        }

        boolean partial = providerPartial || unavailable > 0 || !twr.reliable();
        if (points.isEmpty() && !displayTimes.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Adequate historical prices are unavailable for this portfolio"
            );
        }
        String returnMethod = !twr.reliable()
                ? "UNAVAILABLE"
                : estimated || twr.estimated() ? "ESTIMATED_TWR" : "TWR";
        List<String> limitations = List.of(
                "Equity prices are raw and unadjusted; splits, dividends, and other corporate actions are not modeled."
        );
        OffsetDateTime availableFrom = points.isEmpty() ? null : points.getFirst().timestamp();
        OffsetDateTime availableTo = points.isEmpty() ? null : points.getLast().timestamp();
        return new PortfolioPerformanceDTO(
                range.getApiValue(), range.getTimeframe().getDatabaseValue(), "USD",
                availableFrom, availableTo, partial, returnMethod, unavailable,
                limitations, points
        );
    }

    /** Computes one historical USD valuation without constraining the caller to chart ranges. */
    public PortfolioPerformancePointDTO getValuation(
            Long clientId,
            OffsetDateTime timestamp,
            HistoricalTimeframe timeframe
    ) {
        Objects.requireNonNull(clientId, "clientId is required");
        Objects.requireNonNull(timestamp, "timestamp is required");
        Objects.requireNonNull(timeframe, "timeframe is required");
        ClientEntity client = clientRepository.findById(clientId);
        if (client == null) {
            throw new ClientNotFoundException("Client not found with id: " + clientId);
        }
        if (client.getCreatedAt() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Account creation time is missing");
        }
        OffsetDateTime createdAt = PortfolioFinancialHistoryService.accountCreatedAt(
                client.getCreatedAt()
        );
        if (timestamp.isBefore(createdAt)) {
            throw new IllegalArgumentException("Valuation timestamp predates account creation");
        }
        PortfolioFinancialState state = financialHistoryService.reconstruct(
                clientId, List.of(timestamp)
        ).states().getFirst();
        Map<Long, InstrumentHistoryService.PriceHistory> prices = loadPrices(
                state.instrumentQuantities().keySet(), timeframe, List.of(timestamp)
        );
        Valuation valuation = value(state, prices);
        if (!valuation.available()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Adequate historical prices are unavailable for this valuation"
            );
        }
        return new PortfolioPerformancePointDTO(
                timestamp, money(valuation.cash()), money(valuation.holdings()),
                money(valuation.total()), null,
                valuation.estimated() ? "CARRIED_FORWARD" : "COMPLETE"
        );
    }

    private Map<Long, InstrumentHistoryService.PriceHistory> loadPrices(
            Set<Long> instrumentIds,
            HistoricalTimeframe timeframe,
            List<OffsetDateTime> valuationTimes
    ) {
        Map<Long, InstrumentHistoryService.PriceHistory> result = new LinkedHashMap<>();
        if (instrumentIds.isEmpty() || valuationTimes.isEmpty()) {
            return result;
        }
        OffsetDateTime start = valuationTimes.getFirst();
        OffsetDateTime end = valuationTimes.getLast().plus(completionStep(timeframe));
        for (Long instrumentId : instrumentIds) {
            InstrumentHistoryService.PriceHistory history =
                    instrumentHistoryService.loadCompletedPriceHistory(
                            instrumentId, timeframe, start, end
                    );
            validateUsdValuation(history.instrument());
            result.put(instrumentId, history);
        }
        return result;
    }

    private void validateUsdValuation(InstrumentEntity instrument) {
        boolean usdCurrency = "USD".equalsIgnoreCase(instrument.getCurrency());
        String symbol = instrument.getSymbol() == null ? "" : instrument.getSymbol().toUpperCase();
        boolean usdQuotedPair = symbol.endsWith("/USD");
        boolean supported = instrument.getMarketCode() == MarketCode.IEX && usdCurrency
                || instrument.getMarketCode() == MarketCode.CRYPTO && usdCurrency && usdQuotedPair
                || instrument.getMarketCode() == MarketCode.FX && usdCurrency && usdQuotedPair;
        if (!supported) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Portfolio instrument " + instrument.getSymbol()
                            + " cannot be valued consistently in USD"
            );
        }
    }

    private Valuation value(
            PortfolioFinancialState state,
            Map<Long, InstrumentHistoryService.PriceHistory> priceHistories
    ) {
        BigDecimal holdings = BigDecimal.ZERO;
        boolean estimated = false;
        for (Map.Entry<Long, BigDecimal> position : state.instrumentQuantities().entrySet()) {
            InstrumentHistoryService.PriceHistory history = priceHistories.get(position.getKey());
            Price price = history == null ? null : priceAt(history, state.timestamp());
            if (price == null) {
                return Valuation.unavailable(state.cashBalance());
            }
            holdings = holdings.add(
                    position.getValue().multiply(price.value(), CALCULATION_CONTEXT),
                    CALCULATION_CONTEXT
            );
            estimated |= price.carriedForward();
        }
        return new Valuation(
                state.cashBalance(), holdings,
                state.cashBalance().add(holdings, CALCULATION_CONTEXT), true, estimated
        );
    }

    private Price priceAt(
            InstrumentHistoryService.PriceHistory history,
            OffsetDateTime valuationTime
    ) {
        HistoricalBar selected = null;
        OffsetDateTime selectedCompletedAt = null;
        for (HistoricalBar bar : history.bars()) {
            OffsetDateTime completedAt = completedAt(
                    history.instrument(), history.timeframe(), bar.barStart()
            );
            if (!completedAt.isAfter(valuationTime)
                    && (selectedCompletedAt == null || completedAt.isAfter(selectedCompletedAt))) {
                selected = bar;
                selectedCompletedAt = completedAt;
            }
        }
        if (selected == null || !freshEnough(
                history.instrument(), history.timeframe(), selectedCompletedAt, valuationTime
        )) {
            return null;
        }
        return new Price(
                selected.closePrice(), !selectedCompletedAt.isEqual(valuationTime)
        );
    }

    private OffsetDateTime completedAt(
            InstrumentEntity instrument,
            HistoricalTimeframe timeframe,
            OffsetDateTime barStart
    ) {
        if (!isUsEquity(instrument)) {
            OffsetDateTime utc = barStart.withOffsetSameInstant(ZoneOffset.UTC);
            return switch (timeframe) {
                case FIVE_MINUTES -> utc.plusMinutes(5);
                case THIRTY_MINUTES -> utc.plusMinutes(30);
                case ONE_DAY -> utc.plusDays(1);
                case ONE_WEEK -> utc.plusWeeks(1);
                case ONE_MONTH -> utc.plusMonths(1);
            };
        }
        ZonedDateTime eastern = barStart.atZoneSameInstant(NEW_YORK);
        return switch (timeframe) {
            case FIVE_MINUTES -> barStart.plusMinutes(5);
            case THIRTY_MINUTES -> barStart.plusMinutes(30);
            case ONE_DAY -> eastern.toLocalDate().atTime(EQUITY_CLOSE)
                    .atZone(NEW_YORK).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
            case ONE_WEEK -> eastern.toLocalDate()
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .plusDays(4).atTime(EQUITY_CLOSE).atZone(NEW_YORK)
                    .toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
            case ONE_MONTH -> YearMonth.from(eastern).plusMonths(1).atDay(1)
                    .atStartOfDay(NEW_YORK).toOffsetDateTime()
                    .withOffsetSameInstant(ZoneOffset.UTC);
        };
    }

    private boolean freshEnough(
            InstrumentEntity instrument,
            HistoricalTimeframe timeframe,
            OffsetDateTime completedAt,
            OffsetDateTime valuationTime
    ) {
        Duration age = Duration.between(completedAt, valuationTime);
        if (age.isNegative()) {
            return false;
        }
        Duration maximum = switch (timeframe) {
            case FIVE_MINUTES -> isUsEquity(instrument) || instrument.getMarketCode() == MarketCode.FX
                    ? Duration.ofDays(4) : Duration.ofMinutes(10);
            case THIRTY_MINUTES -> isUsEquity(instrument) || instrument.getMarketCode() == MarketCode.FX
                    ? Duration.ofDays(4) : Duration.ofHours(1);
            case ONE_DAY -> Duration.ofDays(8);
            case ONE_WEEK -> Duration.ofDays(16);
            case ONE_MONTH -> Duration.ofDays(45);
        };
        return age.compareTo(maximum) <= 0;
    }

    private TwrResult calculateTwr(
            List<OffsetDateTime> displayTimes,
            List<PortfolioFinancialEvent> externalFlows,
            Map<OffsetDateTime, Valuation> valuations
    ) {
        Map<OffsetDateTime, BigDecimal> returns = new HashMap<>();
        if (displayTimes.isEmpty()) {
            return new TwrResult(returns, true, false);
        }
        List<PortfolioFinancialEvent> flows = externalFlows.stream()
                .sorted(Comparator.comparing(PortfolioFinancialEvent::timestamp)
                        .thenComparing(PortfolioFinancialEvent::transactionId))
                .toList();
        int flowIndex = 0;
        BigDecimal linkedFactor = BigDecimal.ONE;
        BigDecimal segmentStart = null;
        boolean everFunded = false;
        boolean reliable = true;
        boolean estimated = false;
        boolean originEstablished = false;

        for (OffsetDateTime displayTime : displayTimes) {
            Valuation displayValuation = valuations.get(displayTime);
            if (!originEstablished && reliable && displayValuation != null
                    && displayValuation.available()) {
                segmentStart = positiveOrNull(displayValuation.total());
                everFunded |= segmentStart != null;
                originEstablished = true;
            }
            while (flowIndex < flows.size()
                    && !flows.get(flowIndex).timestamp().isAfter(displayTime)) {
                OffsetDateTime flowTimestamp = flows.get(flowIndex).timestamp();
                while (flowIndex < flows.size()
                        && flows.get(flowIndex).timestamp().isEqual(flowTimestamp)) {
                    flowIndex++;
                }
                if (flowTimestamp.isEqual(displayTimes.getFirst())) {
                    continue;
                }
                Valuation before = valuations.get(flowTimestamp.minusNanos(1));
                Valuation after = valuations.get(flowTimestamp);
                if (before == null || after == null || !before.available() || !after.available()) {
                    reliable = false;
                    segmentStart = null;
                    continue;
                }
                estimated |= before.estimated() || after.estimated();
                if (segmentStart != null) {
                    linkedFactor = linkedFactor.multiply(
                            before.total().divide(segmentStart, CALCULATION_CONTEXT),
                            CALCULATION_CONTEXT
                    );
                }
                segmentStart = positiveOrNull(after.total());
                everFunded |= segmentStart != null;
            }
            if (!reliable || displayValuation == null || !displayValuation.available()) {
                returns.put(displayTime, null);
            } else if (segmentStart != null) {
                BigDecimal factor = linkedFactor.multiply(
                        displayValuation.total().divide(segmentStart, CALCULATION_CONTEXT),
                        CALCULATION_CONTEXT
                );
                returns.put(displayTime, percent(factor.subtract(BigDecimal.ONE)));
            } else if (everFunded) {
                returns.put(displayTime, percent(linkedFactor.subtract(BigDecimal.ONE)));
            } else {
                returns.put(displayTime, null);
            }
        }
        return new TwrResult(returns, reliable, estimated);
    }

    private List<OffsetDateTime> displayTimes(
            OffsetDateTime start,
            OffsetDateTime end,
            HistoricalTimeframe timeframe
    ) {
        if (start.isAfter(end)) {
            return List.of();
        }
        List<OffsetDateTime> result = new ArrayList<>();
        OffsetDateTime cursor = ceilingBoundary(start, timeframe);
        while (!cursor.isAfter(end)) {
            result.add(cursor);
            cursor = nextBoundary(cursor, timeframe);
        }
        return result;
    }

    private OffsetDateTime rangeStart(OffsetDateTime reference, HistoricalRange range) {
        return switch (range) {
            case ONE_DAY -> reference.minusHours(24);
            case FIVE_DAYS -> reference.minusHours(120);
            case ONE_MONTH -> reference.minusMonths(1);
            case ONE_YEAR -> reference.minusYears(1);
            case FIVE_YEARS -> reference.minusYears(5);
        };
    }

    private OffsetDateTime completedPortfolioBoundary(
            OffsetDateTime reference,
            HistoricalTimeframe timeframe
    ) {
        OffsetDateTime utc = reference.withOffsetSameInstant(ZoneOffset.UTC);
        return switch (timeframe) {
            case FIVE_MINUTES -> truncate(utc, Duration.ofMinutes(5));
            case THIRTY_MINUTES -> truncate(utc, Duration.ofMinutes(30));
            case ONE_DAY -> utc.toLocalDate().atStartOfDay().atOffset(ZoneOffset.UTC);
            case ONE_WEEK -> utc.toLocalDate()
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .atStartOfDay().atOffset(ZoneOffset.UTC);
            case ONE_MONTH -> YearMonth.from(utc).atDay(1)
                    .atStartOfDay().atOffset(ZoneOffset.UTC);
        };
    }

    private OffsetDateTime ceilingBoundary(
            OffsetDateTime timestamp,
            HistoricalTimeframe timeframe
    ) {
        OffsetDateTime floor = completedPortfolioBoundary(timestamp, timeframe);
        return floor.isBefore(timestamp) ? nextBoundary(floor, timeframe) : floor;
    }

    private OffsetDateTime nextBoundary(
            OffsetDateTime timestamp,
            HistoricalTimeframe timeframe
    ) {
        return switch (timeframe) {
            case FIVE_MINUTES -> timestamp.plusMinutes(5);
            case THIRTY_MINUTES -> timestamp.plusMinutes(30);
            case ONE_DAY -> timestamp.plusDays(1);
            case ONE_WEEK -> timestamp.plusWeeks(1);
            case ONE_MONTH -> timestamp.plusMonths(1);
        };
    }

    private Duration completionStep(HistoricalTimeframe timeframe) {
        return switch (timeframe) {
            case FIVE_MINUTES -> Duration.ofMinutes(5);
            case THIRTY_MINUTES -> Duration.ofMinutes(30);
            case ONE_DAY -> Duration.ofDays(1);
            case ONE_WEEK -> Duration.ofDays(7);
            case ONE_MONTH -> Duration.ofDays(32);
        };
    }

    private OffsetDateTime truncate(OffsetDateTime timestamp, Duration duration) {
        long seconds = duration.toSeconds();
        long epoch = timestamp.toEpochSecond();
        return OffsetDateTime.ofInstant(
                Instant.ofEpochSecond(epoch - Math.floorMod(epoch, seconds)), ZoneOffset.UTC
        );
    }

    private boolean isExternalFlow(PortfolioFinancialEvent event) {
        return "DEPOSIT".equals(event.transactionType())
                || "WITHDRAWAL".equals(event.transactionType());
    }

    private boolean isUsEquity(InstrumentEntity instrument) {
        return instrument.getMarketCode() == MarketCode.IEX
                && "EQUITY".equalsIgnoreCase(instrument.getAssetClass());
    }

    private BigDecimal positiveOrNull(BigDecimal value) {
        return value.signum() > 0 ? value : null;
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);
    }

    private BigDecimal percent(BigDecimal value) {
        return value.multiply(ONE_HUNDRED, CALCULATION_CONTEXT)
                .setScale(RETURN_SCALE, RoundingMode.HALF_EVEN);
    }

    private OffsetDateTime later(OffsetDateTime first, OffsetDateTime second) {
        return first.isAfter(second) ? first : second;
    }

    private record Price(BigDecimal value, boolean carriedForward) {
    }

    private record Valuation(
            BigDecimal cash,
            BigDecimal holdings,
            BigDecimal total,
            boolean available,
            boolean estimated
    ) {
        private static Valuation unavailable(BigDecimal cash) {
            return new Valuation(cash, null, null, false, false);
        }
    }

    private record TwrResult(
            Map<OffsetDateTime, BigDecimal> returns,
            boolean reliable,
            boolean estimated
    ) {
    }
}
